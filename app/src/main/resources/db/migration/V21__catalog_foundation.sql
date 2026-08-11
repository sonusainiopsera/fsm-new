-- V21__catalog_foundation.sql
-- Catalog foundation: adds catalog-grade columns, partial unique indexes, audit tables
-- for the customer → site → asset chain.
-- Expand-only: no existing column is renamed or dropped.

-- ============================================================
-- 1. Extend CUSTOMER table
-- ============================================================
ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS account_code            VARCHAR(20),
    ADD COLUMN IF NOT EXISTS legal_name              VARCHAR(255),
    ADD COLUMN IF NOT EXISTS primary_contact_name    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS primary_contact_email   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS primary_contact_phone   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS billing_address         TEXT,
    ADD COLUMN IF NOT EXISTS active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS deactivated_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS relationship_ended_on   DATE,
    ADD COLUMN IF NOT EXISTS created_by              UUID,
    ADD COLUMN IF NOT EXISTS updated_at              TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by              UUID;

-- Partial unique: account_code unique among active customers only
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_account_code_active
    ON customer (account_code)
    WHERE active = TRUE AND account_code IS NOT NULL;

-- Lower-case index for free-text search on legal_name
CREATE INDEX IF NOT EXISTS idx_customer_legal_name_lower
    ON customer (lower(legal_name))
    WHERE legal_name IS NOT NULL;

-- ============================================================
-- 2. Extend SITE table
-- ============================================================
ALTER TABLE site
    ADD COLUMN IF NOT EXISTS site_code       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS display_name    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS access_notes    TEXT,
    ADD COLUMN IF NOT EXISTS active          BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS deactivated_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_by      UUID,
    ADD COLUMN IF NOT EXISTS updated_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by      UUID;

-- Partial unique: site_code unique per customer among active sites
CREATE UNIQUE INDEX IF NOT EXISTS uq_site_site_code_customer_active
    ON site (customer_id, site_code)
    WHERE active = TRUE AND site_code IS NOT NULL;

-- Support customer-scoped lookup
CREATE INDEX IF NOT EXISTS idx_site_customer_id ON site (customer_id);

-- ============================================================
-- 3. Extend ASSET table
-- ============================================================
ALTER TABLE asset
    ADD COLUMN IF NOT EXISTS asset_tag       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS category        VARCHAR(100),
    ADD COLUMN IF NOT EXISTS active          BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS deactivated_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_by      UUID,
    ADD COLUMN IF NOT EXISTS updated_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by      UUID;

-- Partial unique: asset_tag unique per site among active assets
CREATE UNIQUE INDEX IF NOT EXISTS uq_asset_tag_site_active
    ON asset (site_id, asset_tag)
    WHERE active = TRUE AND asset_tag IS NOT NULL;

-- Support site-scoped lookup
CREATE INDEX IF NOT EXISTS idx_asset_site_id ON asset (site_id);

-- ============================================================
-- 4. CUSTOMER_AUD — Envers audit table for CustomerAccount
-- ============================================================
CREATE TABLE customer_aud (
    id                      UUID        NOT NULL,
    REV                     INTEGER     NOT NULL,
    REVTYPE                 SMALLINT,
    name                    VARCHAR(255),
    contact_email           VARCHAR(255),
    phone                   VARCHAR(50),
    account_code            VARCHAR(20),
    legal_name              VARCHAR(255),
    primary_contact_name    VARCHAR(255),
    primary_contact_email   VARCHAR(255),
    primary_contact_phone   VARCHAR(50),
    billing_address         TEXT,
    active                  BOOLEAN,
    deactivated_at          TIMESTAMPTZ,
    relationship_ended_on   DATE,
    created_at              TIMESTAMPTZ,
    created_by              UUID,
    updated_at              TIMESTAMPTZ,
    updated_by              UUID,
    version                 INTEGER,
    CONSTRAINT pk_customer_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_customer_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX IF NOT EXISTS brin_customer_aud_rev ON customer_aud USING BRIN (REV);

-- ============================================================
-- 5. ASSET_AUD — Envers audit table for Asset
-- ============================================================
CREATE TABLE asset_aud (
    id             UUID         NOT NULL,
    REV            INTEGER      NOT NULL,
    REVTYPE        SMALLINT,
    site_id        UUID,
    serial_number  VARCHAR(100),
    model          VARCHAR(255),
    manufacturer   VARCHAR(255),
    installed_at   TIMESTAMPTZ,
    asset_tag      VARCHAR(100),
    category       VARCHAR(100),
    active         BOOLEAN,
    deactivated_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ,
    created_by     UUID,
    updated_at     TIMESTAMPTZ,
    updated_by     UUID,
    version        INTEGER,
    CONSTRAINT pk_asset_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_asset_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX IF NOT EXISTS brin_asset_aud_rev ON asset_aud USING BRIN (REV);

-- ============================================================
-- 6. ALTER SITE_AUD to add new catalog columns
-- site_aud was created in V5 with: id, REV, REVTYPE, name, customer_id, created_at, version
-- Expand-only: adding new columns only.
-- ============================================================
ALTER TABLE site_aud
    ADD COLUMN IF NOT EXISTS site_code      VARCHAR(20),
    ADD COLUMN IF NOT EXISTS display_name   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS access_notes   TEXT,
    ADD COLUMN IF NOT EXISTS active         BOOLEAN,
    ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS address_line1  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS city           VARCHAR(100),
    ADD COLUMN IF NOT EXISTS postcode       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS latitude       NUMERIC(9, 6),
    ADD COLUMN IF NOT EXISTS longitude      NUMERIC(9, 6),
    ADD COLUMN IF NOT EXISTS created_by     UUID,
    ADD COLUMN IF NOT EXISTS updated_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by     UUID;

-- ============================================================
-- 7. Audit table grants (optional fsapi_audit_reader role)
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fsapi_audit_reader') THEN
        EXECUTE 'GRANT SELECT ON customer_aud TO fsapi_audit_reader';
        EXECUTE 'GRANT SELECT ON asset_aud    TO fsapi_audit_reader';
    END IF;
END $$;
