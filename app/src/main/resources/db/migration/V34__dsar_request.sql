-- V34: Data Subject Access Request (DSAR) queue and export artifacts
-- Supports GDPR/CCPA data subject rights: access, portability, rectification, erasure.
-- BR-26, O7 (100% fulfilled within 30 days), Phase 3 closed-beta gate.

CREATE TABLE dsar_request (
    id                    UUID        NOT NULL,
    request_type          VARCHAR(30) NOT NULL
        CONSTRAINT chk_dsar_request_type
            CHECK (request_type IN ('ACCESS', 'PORTABILITY', 'RECTIFICATION', 'ERASURE')),
    subject_type          VARCHAR(100) NOT NULL,
    subject_id            UUID        NOT NULL,
    submitted_at          TIMESTAMPTZ NOT NULL,
    due_at                TIMESTAMPTZ NOT NULL,
    identity_verified_at  TIMESTAMPTZ,
    verification_method   VARCHAR(50),
    state                 VARCHAR(30) NOT NULL DEFAULT 'RECEIVED'
        CONSTRAINT chk_dsar_state
            CHECK (state IN ('RECEIVED', 'IDENTITY_PENDING', 'VERIFIED', 'IN_PROGRESS',
                             'FULFILLED', 'REJECTED', 'WITHDRAWN')),
    assigned_handler      UUID,
    outcome               VARCHAR(50),
    outcome_note          TEXT,
    attempt_count         INTEGER     NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(255),
    updated_at            TIMESTAMPTZ,
    updated_by            VARCHAR(255),
    version               INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_dsar_request PRIMARY KEY (id)
);

-- Query pattern: list by state ordered by due_at for O7 monitoring
CREATE INDEX idx_dsar_request_state_due ON dsar_request (state, due_at);
-- Query pattern: duplicate detection and subject lookup
CREATE INDEX idx_dsar_request_subject ON dsar_request (subject_type, subject_id);

CREATE TABLE dsar_export_artifact (
    id              UUID        NOT NULL,
    dsar_request_id UUID        NOT NULL
        CONSTRAINT fk_dsar_export_request REFERENCES dsar_request (id),
    storage_key     VARCHAR(500),
    manifest        JSONB,
    export_json     TEXT,
    byte_size       BIGINT,
    generated_at    TIMESTAMPTZ,
    disposed_at     TIMESTAMPTZ,
    CONSTRAINT pk_dsar_export_artifact PRIMARY KEY (id)
);

CREATE INDEX idx_dsar_artifact_request ON dsar_export_artifact (dsar_request_id);

-- Envers audit tables
CREATE TABLE dsar_request_aud (
    id                    UUID        NOT NULL,
    rev                   INTEGER     NOT NULL REFERENCES REVINFO (rev),
    revtype               SMALLINT,
    request_type          VARCHAR(30),
    subject_type          VARCHAR(100),
    subject_id            UUID,
    submitted_at          TIMESTAMPTZ,
    due_at                TIMESTAMPTZ,
    identity_verified_at  TIMESTAMPTZ,
    verification_method   VARCHAR(50),
    state                 VARCHAR(30),
    assigned_handler      UUID,
    outcome               VARCHAR(50),
    outcome_note          TEXT,
    attempt_count         INTEGER,
    created_at            TIMESTAMPTZ,
    created_by            VARCHAR(255),
    updated_at            TIMESTAMPTZ,
    updated_by            VARCHAR(255),
    version               INTEGER,
    CONSTRAINT pk_dsar_request_aud PRIMARY KEY (id, rev)
);
