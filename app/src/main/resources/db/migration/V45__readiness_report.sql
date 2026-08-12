-- V45__readiness_report.sql
-- Certification data-readiness completeness report and gate (WO-122).
--
-- readiness_requirement: configurable completeness definition — which profile
--   fields and certification type codes are mandatory for dispatch go-live.
--   @Audited via Envers so changes to the definition are traceable.
--
-- readiness_snapshot: weekly persisted aggregate; idempotent on iso_week so
--   regenerating the same period upserts rather than duplicating rows.
--
-- Architecture decision: stored in the workforce module (not analytics) because
--   this is a Phase-1 governance artefact, not an operational KPI.

-- ============================================================
-- readiness_requirement
-- ============================================================
CREATE TABLE readiness_requirement (
    id                      UUID         NOT NULL,
    requirement_kind        TEXT         NOT NULL
        CHECK (requirement_kind IN ('PROFILE_FIELD', 'CERTIFICATION_TYPE')),
    field_name              TEXT,
    certification_type_code TEXT,
    technician_category     TEXT,
    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    version                 INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ,

    CONSTRAINT pk_readiness_requirement PRIMARY KEY (id),
    CONSTRAINT chk_rr_profile_has_field
        CHECK (requirement_kind <> 'PROFILE_FIELD' OR field_name IS NOT NULL),
    CONSTRAINT chk_rr_cert_has_code
        CHECK (requirement_kind <> 'CERTIFICATION_TYPE' OR certification_type_code IS NOT NULL)
);

-- Envers audit table for readiness_requirement
CREATE TABLE readiness_requirement_aud (
    id                      UUID        NOT NULL,
    REV                     INTEGER     NOT NULL,
    REVTYPE                 SMALLINT,
    requirement_kind        TEXT,
    field_name              TEXT,
    certification_type_code TEXT,
    technician_category     TEXT,
    active                  BOOLEAN,
    version                 INTEGER,
    CONSTRAINT pk_readiness_requirement_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_readiness_requirement_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- readiness_snapshot
-- ============================================================
CREATE TABLE readiness_snapshot (
    id                   UUID           NOT NULL,
    iso_week             TEXT           NOT NULL,
    readiness_percent    NUMERIC(5, 2),
    complete_technicians INTEGER        NOT NULL DEFAULT 0,
    active_technicians   INTEGER        NOT NULL DEFAULT 0,
    gate_met             BOOLEAN        NOT NULL DEFAULT FALSE,
    definition_version   TEXT           NOT NULL,
    generated_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_readiness_snapshot       PRIMARY KEY (id),
    CONSTRAINT uq_readiness_snapshot_week  UNIQUE (iso_week)
);

CREATE INDEX idx_readiness_snapshot_generated_at
    ON readiness_snapshot (generated_at DESC);

-- ============================================================
-- Placeholder seed requirements (taxonomy unratified — to be confirmed by ops)
-- UUID prefix: 00000000-0000-7045-8000-XXXXXXXXXXXX
-- Two mandatory requirements cover the minimum viable completeness definition:
--   1. Profile field: employeeCode must be present
--   2. Certification type: GAS_SAFE must be current
-- The word PLACEHOLDER marks these as not yet ratified.
-- ============================================================
INSERT INTO readiness_requirement
    (id, requirement_kind, field_name, certification_type_code, active)
VALUES
    ('00000000-0000-7045-8000-000000000001',
     'PROFILE_FIELD', 'employeeCode', NULL, TRUE),
    ('00000000-0000-7045-8000-000000000002',
     'CERTIFICATION_TYPE', NULL, 'GAS_SAFE', TRUE)
ON CONFLICT (id) DO NOTHING;
