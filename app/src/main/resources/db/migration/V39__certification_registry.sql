-- =============================================================================
-- V39: Certification registry — replaces the V1 placeholder (WO-119)
-- Drops the old technician_certification placeholder and creates a proper
-- certification_type reference table and a new technician_certification table.
-- Envers audit tables are recreated with the new schema.
-- =============================================================================

-- Drop V5 audit table (old schema) and V1 placeholder table
DROP TABLE IF EXISTS technician_certification_aud;
DROP TABLE IF EXISTS technician_certification CASCADE;

-- ---------------------------------------------------------------------------
-- certification_type: runtime-configurable reference catalogue
-- regulated = true  → dispatch eligibility hard gate (BR-01, no override)
-- regulated = false → advisory warning only
-- ---------------------------------------------------------------------------
CREATE TABLE certification_type (
    id                      UUID         NOT NULL PRIMARY KEY,
    code                    VARCHAR(50)  NOT NULL,
    display_name            VARCHAR(255) NOT NULL,
    regulated               BOOLEAN      NOT NULL DEFAULT false,
    default_validity_months INTEGER,
    active                  BOOLEAN      NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT uq_certification_type_code UNIQUE (code),
    CONSTRAINT chk_ct_validity_months CHECK (default_validity_months IS NULL OR default_validity_months > 0)
);

-- ---------------------------------------------------------------------------
-- technician_certification: per-technician certification records
-- Currency is computed as expires_on >= :atDate — never a stored flag.
-- ---------------------------------------------------------------------------
CREATE TABLE technician_certification (
    id                    UUID         NOT NULL PRIMARY KEY,
    technician_id         UUID         NOT NULL REFERENCES technician(id),
    certification_type_id UUID         NOT NULL REFERENCES certification_type(id),
    certificate_reference VARCHAR(100),
    issued_on             DATE         NOT NULL,
    expires_on            DATE,
    issuing_body          VARCHAR(255),
    active                BOOLEAN      NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT chk_tc_expires_after_issued
        CHECK (expires_on IS NULL OR expires_on >= issued_on)
);

-- One active certification per technician per type (partial unique index)
CREATE UNIQUE INDEX uq_tc_technician_type_active
    ON technician_certification(technician_id, certification_type_id)
    WHERE active = true;

-- Eligibility query and expiry-sweep index
CREATE INDEX idx_tc_type_expires
    ON technician_certification(certification_type_id, expires_on);

CREATE INDEX idx_tc_technician_expires
    ON technician_certification(technician_id, expires_on);

-- =============================================================================
-- Envers audit tables
-- =============================================================================

CREATE TABLE certification_type_aud (
    id                      UUID         NOT NULL,
    rev                     INTEGER      NOT NULL,
    revtype                 SMALLINT     NOT NULL,
    code                    VARCHAR(50),
    display_name            VARCHAR(255),
    regulated               BOOLEAN,
    default_validity_months INTEGER,
    active                  BOOLEAN,
    created_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ,
    CONSTRAINT pk_certification_type_aud    PRIMARY KEY (id, rev),
    CONSTRAINT fk_certification_type_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE TABLE technician_certification_aud (
    id                    UUID         NOT NULL,
    rev                   INTEGER      NOT NULL,
    revtype               SMALLINT     NOT NULL,
    technician_id         UUID,
    certification_type_id UUID,
    certificate_reference VARCHAR(100),
    issued_on             DATE,
    expires_on            DATE,
    issuing_body          VARCHAR(255),
    active                BOOLEAN,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    CONSTRAINT pk_technician_certification_aud    PRIMARY KEY (id, rev),
    CONSTRAINT fk_technician_certification_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- =============================================================================
-- Seed placeholder certification types
-- Clearly labelled as unratified placeholders (BR-07 taxonomy Q11 open).
-- At least 3 regulated and 3 non-regulated types required by AC-8.
-- =============================================================================
INSERT INTO certification_type (id, code, display_name, regulated, default_validity_months) VALUES
    -- Regulated types (hard dispatch gate — no override)
    (gen_random_uuid(), 'GAS_SAFE',        '[PLACEHOLDER] Gas Safe Registration',           true,  12),
    (gen_random_uuid(), 'REFRIGERANT_F_GAS','[PLACEHOLDER] Refrigerant Handling (F-Gas)',   true,  36),
    (gen_random_uuid(), 'ELECTRICAL_18TH', '[PLACEHOLDER] 18th Edition Wiring Regulations', true,  60),
    (gen_random_uuid(), 'ASBESTOS_AWARE',  '[PLACEHOLDER] Asbestos Awareness Certificate',  true,  12),
    -- Non-regulated types (advisory warning only)
    (gen_random_uuid(), 'WORKING_AT_HEIGHT','[PLACEHOLDER] Working at Height',              false, 24),
    (gen_random_uuid(), 'FIRST_AID',        '[PLACEHOLDER] First Aid at Work',              false, 36),
    (gen_random_uuid(), 'MANUAL_HANDLING',  '[PLACEHOLDER] Manual Handling',                false, 24)
ON CONFLICT (code) DO NOTHING;
