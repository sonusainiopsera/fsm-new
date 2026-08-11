-- =============================================================================
-- V22: Catalog module expansion — customer, site, asset reference data
-- =============================================================================
-- Expand-only DDL: adds new columns, indexes, and audit tables to the existing
-- customer, site, and asset tables created in V1. No destructive changes.
-- All new columns are nullable to preserve backward compatibility with
-- in-flight application versions during rolling deploys.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- customer: expand with catalog fields
-- ---------------------------------------------------------------------------
ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS account_code          VARCHAR(50),
    ADD COLUMN IF NOT EXISTS legal_name            VARCHAR(255),
    ADD COLUMN IF NOT EXISTS primary_contact_name  VARCHAR(255),     -- CONFIDENTIAL: PII
    ADD COLUMN IF NOT EXISTS primary_contact_email VARCHAR(320),     -- CONFIDENTIAL: PII
    ADD COLUMN IF NOT EXISTS primary_contact_phone VARCHAR(50),      -- CONFIDENTIAL: PII
    ADD COLUMN IF NOT EXISTS deactivated_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS relationship_ended_on DATE;

COMMENT ON COLUMN customer.account_code          IS 'Unique short reference code used in dispatch and reports';
COMMENT ON COLUMN customer.legal_name            IS 'Legal registered company name; replaces legacy name column over time';
COMMENT ON COLUMN customer.primary_contact_name  IS 'CONFIDENTIAL – Primary contact person name, mask in non-production';
COMMENT ON COLUMN customer.primary_contact_email IS 'CONFIDENTIAL – Primary contact email, mask in non-production';
COMMENT ON COLUMN customer.primary_contact_phone IS 'CONFIDENTIAL – Primary contact phone, mask in non-production';
COMMENT ON COLUMN customer.deactivated_at        IS 'Set when is_active transitions to false; null for active records';
COMMENT ON COLUMN customer.relationship_ended_on IS 'Date the business relationship ended; drives data-retention purge after 12 months';

-- Partial unique index: account_code must be unique among active customers only
-- (deactivated customers may retain their code for audit purposes)
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_account_code_active
    ON customer (account_code)
    WHERE is_active = true AND account_code IS NOT NULL;

-- Case-insensitive search index on legal_name
CREATE INDEX IF NOT EXISTS idx_customer_legal_name_lower
    ON customer (lower(legal_name))
    WHERE legal_name IS NOT NULL;

-- ---------------------------------------------------------------------------
-- site: expand with catalog fields
-- ---------------------------------------------------------------------------
ALTER TABLE site
    ADD COLUMN IF NOT EXISTS site_code    VARCHAR(50),
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS postcode     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS access_notes TEXT,
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMPTZ;

COMMENT ON COLUMN site.site_code     IS 'Short reference code unique per active customer';
COMMENT ON COLUMN site.display_name  IS 'Human-readable site name shown in dispatch UI; replaces legacy name column over time';
COMMENT ON COLUMN site.postcode      IS 'Postal code for geocoding and regional routing';
COMMENT ON COLUMN site.access_notes  IS 'CONFIDENTIAL – Site access instructions, entry codes; mask in non-production';
COMMENT ON COLUMN site.deactivated_at IS 'Set when is_active transitions to false';

-- Partial unique index: (customer_id, site_code) unique among active sites
CREATE UNIQUE INDEX IF NOT EXISTS uq_site_customer_site_code_active
    ON site (customer_id, site_code)
    WHERE is_active = true AND site_code IS NOT NULL;

-- Case-insensitive search index on display_name
CREATE INDEX IF NOT EXISTS idx_site_display_name_lower
    ON site (lower(display_name))
    WHERE display_name IS NOT NULL;

-- ---------------------------------------------------------------------------
-- asset: expand with catalog fields
-- ---------------------------------------------------------------------------
ALTER TABLE asset
    ADD COLUMN IF NOT EXISTS asset_tag     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS manufacturer  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS category      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS installed_on  DATE,
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMPTZ;

COMMENT ON COLUMN asset.asset_tag      IS 'Asset tag label unique per active site; the join key for first-time-fix metric';
COMMENT ON COLUMN asset.manufacturer   IS 'Equipment manufacturer name for service routing and parts lookup';
COMMENT ON COLUMN asset.category       IS 'Asset category code (HVAC, ELECTRICAL, PLUMBING, …)';
COMMENT ON COLUMN asset.installed_on   IS 'Installation date; used for warranty and scheduled maintenance windows';
COMMENT ON COLUMN asset.deactivated_at IS 'Set when is_active transitions to false';

-- Partial unique index: (site_id, asset_tag) unique among active assets
CREATE UNIQUE INDEX IF NOT EXISTS uq_asset_site_asset_tag_active
    ON asset (site_id, asset_tag)
    WHERE is_active = true AND asset_tag IS NOT NULL;

-- Search indexes
CREATE INDEX IF NOT EXISTS idx_asset_category ON asset (category)
    WHERE category IS NOT NULL;

-- ---------------------------------------------------------------------------
-- customer_AUD: Envers audit table for customer
-- ---------------------------------------------------------------------------
-- Customer was not previously @Audited; this table enables Hibernate Envers
-- to record create/update/deactivate revisions going forward.
-- Columns mirror the full customer table (including new columns). version is
-- excluded per do_not_audit_optimistic_locking_field=true.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customer_aud (
    id                     UUID        NOT NULL,
    rev                    INTEGER     NOT NULL,
    revtype                SMALLINT    NOT NULL,
    -- legacy columns from V1
    name                   VARCHAR(255),
    contact_email          VARCHAR(320),
    contact_phone          VARCHAR(50),
    billing_address        VARCHAR(500),
    is_active              BOOLEAN,
    created_at             TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ,
    -- new catalog columns from V22
    account_code           VARCHAR(50),
    legal_name             VARCHAR(255),
    primary_contact_name   VARCHAR(255),
    primary_contact_email  VARCHAR(320),
    primary_contact_phone  VARCHAR(50),
    deactivated_at         TIMESTAMPTZ,
    relationship_ended_on  DATE,
    CONSTRAINT pk_customer_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_customer_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX IF NOT EXISTS idx_customer_aud_rev_brin
    ON customer_aud USING brin (rev);

-- ---------------------------------------------------------------------------
-- site_AUD: add new columns introduced in V22 to existing audit table
-- ---------------------------------------------------------------------------
-- site_AUD was created in V5 with the original site columns.
-- New columns must be added so Envers can populate them.
-- ---------------------------------------------------------------------------
ALTER TABLE site_aud
    ADD COLUMN IF NOT EXISTS site_code     VARCHAR(50),
    ADD COLUMN IF NOT EXISTS display_name  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS postcode      VARCHAR(20),
    ADD COLUMN IF NOT EXISTS access_notes  TEXT,
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- asset_AUD: Envers audit table for asset
-- ---------------------------------------------------------------------------
-- Asset was not previously @Audited; this table enables revision history.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_aud (
    id             UUID        NOT NULL,
    rev            INTEGER     NOT NULL,
    revtype        SMALLINT    NOT NULL,
    -- legacy columns from V1
    site_id        UUID,
    name           VARCHAR(255),
    asset_type     VARCHAR(100),
    serial_no      VARCHAR(100),
    model          VARCHAR(255),
    is_active      BOOLEAN,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    -- new catalog columns from V22
    asset_tag      VARCHAR(100),
    manufacturer   VARCHAR(100),
    category       VARCHAR(100),
    installed_on   DATE,
    deactivated_at TIMESTAMPTZ,
    CONSTRAINT pk_asset_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_asset_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX IF NOT EXISTS idx_asset_aud_rev_brin
    ON asset_aud USING brin (rev);
