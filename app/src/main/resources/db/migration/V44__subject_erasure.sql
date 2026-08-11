-- =============================================================================
-- V44: Subject erasure tombstone and additive column for export artifact disposal.
-- WO-191: Rectification and cryptographic erasure of subject data.
--
-- Design notes:
--   - subject_erasure is append-only: no UPDATE or DELETE is ever issued against it.
--   - NO column may hold a personal-data value (only UUIDs, timestamps, varchar codes,
--     and jsonb structures whose shape is validated to contain no PII).
--   - The unique partial index on (subject_type, subject_id) WHERE outcome='COMPLETED'
--     ensures at most one completed erasure per subject while permitting a REFUSED
--     record followed by a later successful erasure.
--   - Envers *_AUD and REVINFO tables are never modified by any erasure operation;
--     erasure works exclusively by key destruction.
-- =============================================================================

CREATE TABLE subject_erasure (
    id                  UUID         NOT NULL,
    dsar_request_id     UUID         NOT NULL REFERENCES dsar_request(id),
    subject_type        VARCHAR(50)  NOT NULL,
    subject_id          UUID         NOT NULL,
    key_reference       VARCHAR(255) NOT NULL,        -- opaque key identifier, not a key value
    erased_at           TIMESTAMPTZ  NOT NULL,
    actor               VARCHAR(255) NOT NULL,        -- principal name, not a personal value
    erased_sections     JSONB        NOT NULL DEFAULT '[]',
    verification_result JSONB        NOT NULL DEFAULT '{}',
    outcome             VARCHAR(30)  NOT NULL,
    refusal_reason      VARCHAR(500),
    version             INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_subject_erasure PRIMARY KEY (id),
    CONSTRAINT fk_subject_erasure_dsar FOREIGN KEY (dsar_request_id) REFERENCES dsar_request(id),
    CONSTRAINT chk_subject_erasure_outcome CHECK (
        outcome IN ('COMPLETED', 'IDEMPOTENT_NOOP', 'REFUSED')
    )
);

-- Only one COMPLETED erasure may exist per subject (idempotency guard).
-- REFUSED and IDEMPOTENT_NOOP records are not constrained.
CREATE UNIQUE INDEX uq_subject_erasure_completed
    ON subject_erasure (subject_type, subject_id)
    WHERE outcome = 'COMPLETED';

-- Index for GET /api/v1/privacy/erasures/{id} and per-subject lookups.
CREATE INDEX idx_subject_erasure_subject
    ON subject_erasure (subject_type, subject_id);

-- Envers audit table for subject_erasure (append-only, never updated/deleted).
CREATE TABLE subject_erasure_aud (
    id                  UUID         NOT NULL,
    rev                 INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype             SMALLINT,
    dsar_request_id     UUID,
    subject_type        VARCHAR(50),
    subject_id          UUID,
    key_reference       VARCHAR(255),
    erased_at           TIMESTAMPTZ,
    actor               VARCHAR(255),
    erased_sections     JSONB,
    verification_result JSONB,
    outcome             VARCHAR(30),
    refusal_reason      VARCHAR(500),
    created_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ,

    CONSTRAINT pk_subject_erasure_aud PRIMARY KEY (id, rev)
);

CREATE INDEX idx_subject_erasure_aud_rev ON subject_erasure_aud USING BRIN (rev);

-- Add disposed_at to dsar_export_artifact if not already present (idempotent).
-- The column was included in V36; this is a no-op guard for older environments.
ALTER TABLE dsar_export_artifact
    ADD COLUMN IF NOT EXISTS disposed_at TIMESTAMPTZ;
