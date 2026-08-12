-- V39__certification_registry.sql
-- Certification type registry and per-technician certification records.
-- BR-01 and BR-02 compliance gate: currency is a query-time predicate only —
-- NO stored boolean for is_current, is_valid, current_flag or valid_flag.
--
-- Expand-only: technician_certification gains new columns while old legacy columns
-- (certification_code, issued_at, expires_at) are retained for backward compatibility
-- with existing CertificationCurrencyGuard.

-- ============================================================
-- certification_type — runtime-configurable registry
-- regulated = true  → hard 422 refusal on assignment
-- regulated = false → advisory warning only
-- ============================================================
CREATE TABLE certification_type (
    id                     UUID         NOT NULL,
    code                   VARCHAR(50)  NOT NULL,
    display_name           VARCHAR(255) NOT NULL,
    regulated              BOOLEAN      NOT NULL DEFAULT FALSE,
    default_validity_months INTEGER,
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version                INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_certification_type      PRIMARY KEY (id),
    CONSTRAINT uq_certification_type_code UNIQUE (code),
    CONSTRAINT chk_cert_type_validity     CHECK (default_validity_months IS NULL OR default_validity_months > 0)
);

CREATE TABLE certification_type_aud (
    id                     UUID        NOT NULL,
    REV                    INTEGER     NOT NULL,
    REVTYPE                SMALLINT,
    code                   VARCHAR(50),
    display_name           VARCHAR(255),
    regulated              BOOLEAN,
    default_validity_months INTEGER,
    active                 BOOLEAN,
    created_at             TIMESTAMPTZ,
    version                INTEGER,
    CONSTRAINT pk_cert_type_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_cert_type_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- Extend technician_certification with new schema columns
-- Old columns (certification_code, issued_at, expires_at) retained for
-- backward compatibility with existing guards — do NOT remove.
-- ============================================================
ALTER TABLE technician_certification
    ADD COLUMN IF NOT EXISTS certification_type_id  UUID,
    ADD COLUMN IF NOT EXISTS certificate_reference  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS issued_on              DATE,
    ADD COLUMN IF NOT EXISTS expires_on             DATE,
    ADD COLUMN IF NOT EXISTS issuing_body           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS active                 BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS version                INTEGER NOT NULL DEFAULT 0;

-- FK to certification_type (nullable: old rows without type_id are legacy)
ALTER TABLE technician_certification
    ADD CONSTRAINT fk_tech_cert_type
        FOREIGN KEY (certification_type_id) REFERENCES certification_type (id)
        NOT VALID;
ALTER TABLE technician_certification VALIDATE CONSTRAINT fk_tech_cert_type;

-- Check: expires_on must be on or after issued_on when both present
ALTER TABLE technician_certification
    ADD CONSTRAINT chk_tech_cert_dates
        CHECK (expires_on IS NULL OR issued_on IS NULL OR expires_on >= issued_on)
        NOT VALID;
ALTER TABLE technician_certification VALIDATE CONSTRAINT chk_tech_cert_dates;

-- Unique active certification per (technician, type) — enforced only for new-schema rows
CREATE UNIQUE INDEX uq_tech_cert_active_type
    ON technician_certification (technician_id, certification_type_id)
    WHERE active = TRUE AND certification_type_id IS NOT NULL;

-- Index to accelerate eligibility query and expiry sweep
CREATE INDEX idx_tech_cert_type_expiry
    ON technician_certification (certification_type_id, expires_on)
    WHERE active = TRUE;

CREATE INDEX idx_tech_cert_technician_expiry
    ON technician_certification (technician_id, expires_on)
    WHERE active = TRUE;

-- ============================================================
-- technician_certification_aud (Envers) — extend to include new columns
-- ============================================================
CREATE TABLE IF NOT EXISTS technician_certification_aud (
    id                     UUID        NOT NULL,
    REV                    INTEGER     NOT NULL,
    REVTYPE                SMALLINT,
    technician_id          UUID,
    certification_code     VARCHAR(50),
    certification_type_id  UUID,
    certificate_reference  VARCHAR(100),
    issued_on              DATE,
    expires_on             DATE,
    issued_at              TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ,
    issuing_body           VARCHAR(255),
    active                 BOOLEAN,
    created_at             TIMESTAMPTZ,
    version                INTEGER,
    CONSTRAINT pk_tech_cert_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_tech_cert_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- Seed placeholder certification types (taxonomy unratified — Q4 2026)
-- UUID prefix: 00000000-0000-7039-8000-XXXXXXXXXXXX
-- Visibly labelled as PLACEHOLDER so no system treats these as ratified.
-- regulated = true  → 3 entries (hard compliance gates)
-- regulated = false → 3 entries (advisory soft checks)
-- ============================================================
INSERT INTO certification_type (id, code, display_name, regulated, default_validity_months, active)
VALUES
    -- Regulated (hard gate — cannot be overridden)
    ('00000000-0000-7039-8000-000000000001',
     'GAS_SAFE',
     '[PLACEHOLDER] Gas Safe Register (Domestic & Commercial)',
     TRUE, 12, TRUE),
    ('00000000-0000-7039-8000-000000000002',
     'REFRIGERANT_F_GAS',
     '[PLACEHOLDER] Refrigerant Handling — F-Gas Category I',
     TRUE, 24, TRUE),
    ('00000000-0000-7039-8000-000000000003',
     'ELECTRICAL_17TH_ED',
     '[PLACEHOLDER] Electrical Installation — 18th Edition Wiring Regulations',
     TRUE, 36, TRUE),
    -- Non-regulated (advisory — returns warning not refusal)
    ('00000000-0000-7039-8000-000000000004',
     'FIRST_AID_BASIC',
     '[PLACEHOLDER] First Aid at Work (Basic)',
     FALSE, 36, TRUE),
    ('00000000-0000-7039-8000-000000000005',
     'WORKING_AT_HEIGHT',
     '[PLACEHOLDER] Working at Height Safety',
     FALSE, 24, TRUE),
    ('00000000-0000-7039-8000-000000000006',
     'ASBESTOS_AWARENESS',
     '[PLACEHOLDER] Asbestos Awareness (Non-Licensed)',
     FALSE, 12, TRUE)
ON CONFLICT (code) DO NOTHING;
