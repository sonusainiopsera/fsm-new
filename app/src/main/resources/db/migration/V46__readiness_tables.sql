-- =============================================================================
-- V46: Certification data-readiness tables (WO-122)
--
-- readiness_requirement: configurable completeness definition.
--   PROFILE_FIELD  → technician profile field must be non-null/non-blank.
--   CERTIFICATION_TYPE → technician must hold a current cert of this type.
--   Envers-audited so definition changes are traceable.
--
-- readiness_snapshot: weekly aggregate snapshot keyed on iso_week.
--   Idempotent via UPSERT on iso_week. Not audited — snapshots are immutable
--   governance artefacts; any recalculation produces a new row value.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- readiness_requirement
-- ---------------------------------------------------------------------------
CREATE TABLE readiness_requirement (
    id                      UUID         NOT NULL PRIMARY KEY,
    requirement_kind        VARCHAR(30)  NOT NULL,
    field_name              VARCHAR(100),             -- populated when kind = PROFILE_FIELD
    certification_type_code VARCHAR(50),              -- populated when kind = CERTIFICATION_TYPE
    technician_category     VARCHAR(100),             -- NULL = applies to all categories
    active                  BOOLEAN      NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT chk_rr_kind CHECK (requirement_kind IN ('PROFILE_FIELD','CERTIFICATION_TYPE')),
    CONSTRAINT chk_rr_field_xor_cert
        CHECK (
            (requirement_kind = 'PROFILE_FIELD'    AND field_name IS NOT NULL              AND certification_type_code IS NULL) OR
            (requirement_kind = 'CERTIFICATION_TYPE' AND certification_type_code IS NOT NULL AND field_name IS NULL)
        )
);

CREATE INDEX idx_readiness_requirement_active ON readiness_requirement(active) WHERE active = true;

-- ---------------------------------------------------------------------------
-- Envers audit table for readiness_requirement
-- ---------------------------------------------------------------------------
CREATE TABLE readiness_requirement_aud (
    id                      UUID         NOT NULL,
    rev                     INTEGER      NOT NULL,
    revtype                 SMALLINT     NOT NULL,
    requirement_kind        VARCHAR(30),
    field_name              VARCHAR(100),
    certification_type_code VARCHAR(50),
    technician_category     VARCHAR(100),
    active                  BOOLEAN,
    created_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ,
    CONSTRAINT pk_readiness_requirement_aud   PRIMARY KEY (id, rev),
    CONSTRAINT fk_readiness_requirement_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- ---------------------------------------------------------------------------
-- readiness_snapshot
-- ---------------------------------------------------------------------------
CREATE TABLE readiness_snapshot (
    id                    UUID          NOT NULL PRIMARY KEY,
    iso_week              VARCHAR(8)    NOT NULL,  -- e.g. "2026-W33"
    readiness_percent     NUMERIC(5,2),            -- NULL when not applicable (zero active techs)
    complete_technicians  INTEGER       NOT NULL,
    active_technicians    INTEGER       NOT NULL,
    gate_met              BOOLEAN       NOT NULL,
    definition_version    VARCHAR(36)   NOT NULL,  -- hash of active requirement IDs
    generated_at          TIMESTAMPTZ   NOT NULL,
    applicable            BOOLEAN       NOT NULL DEFAULT true,

    CONSTRAINT uq_readiness_snapshot_iso_week UNIQUE (iso_week)
);

CREATE INDEX idx_readiness_snapshot_generated_at ON readiness_snapshot(generated_at DESC);

-- ---------------------------------------------------------------------------
-- Seed: placeholder requirements (unratified — label makes this explicit)
-- Covers profile fields: employee_no
-- Covers certifications: GAS_SAFE, ELECTRICAL_18TH (regulated), FIRST_AID (non-reg)
-- Must be labelled [PLACEHOLDER] per BR-07 Q11 open item.
-- ---------------------------------------------------------------------------
INSERT INTO readiness_requirement (id, requirement_kind, field_name, certification_type_code, active, version) VALUES
    (gen_random_uuid(), 'PROFILE_FIELD',      'employee_no',    NULL,               true, 0),
    (gen_random_uuid(), 'CERTIFICATION_TYPE',  NULL,            'GAS_SAFE',         true, 0),
    (gen_random_uuid(), 'CERTIFICATION_TYPE',  NULL,            'ELECTRICAL_18TH',  true, 0)
ON CONFLICT DO NOTHING;
