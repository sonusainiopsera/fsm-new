-- V36: DSAR (Data Subject Access Request) queue with export artifact tracking
-- WO-190: Data subject access and portability export workflow
-- All tables are additive (expand-only); existing schema is never modified.

-- ── dsar_request ─────────────────────────────────────────────────────────────
CREATE TABLE dsar_request (
    id                   UUID         NOT NULL,
    request_type         VARCHAR(20)  NOT NULL,
    subject_type         VARCHAR(50)  NOT NULL,
    subject_id           UUID         NOT NULL,
    submitted_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    due_at               TIMESTAMPTZ  NOT NULL,
    identity_verified_at TIMESTAMPTZ,
    verification_method  VARCHAR(30),
    state                VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    assigned_handler     UUID,
    outcome              VARCHAR(30),
    outcome_note         TEXT,
    attempt_count        INTEGER      NOT NULL DEFAULT 0,
    notes                TEXT,
    version              INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_dsar_request           PRIMARY KEY (id),
    CONSTRAINT chk_dsar_request_type     CHECK (request_type IN ('ACCESS','PORTABILITY','RECTIFICATION','ERASURE')),
    CONSTRAINT chk_dsar_state            CHECK (state IN ('RECEIVED','IDENTITY_PENDING','VERIFIED','IN_PROGRESS','FULFILLED','REJECTED','WITHDRAWN')),
    CONSTRAINT chk_dsar_verification_method CHECK (
        verification_method IS NULL
        OR verification_method IN ('GOVERNMENT_ID','EMAIL_OTP','ACCOUNT_CONFIRM','EXTERNAL_MANUAL')
    ),
    CONSTRAINT chk_dsar_outcome          CHECK (
        outcome IS NULL
        OR outcome IN ('FULFILLED_IN_TIME','FULFILLED_LATE','REJECTED','WITHDRAWN')
    )
);

-- Composite index for the DSAR queue page (state + due_at ordering)
CREATE INDEX idx_dsar_request_state_due    ON dsar_request (state, due_at);
-- Index for duplicate detection and per-subject lookups
CREATE INDEX idx_dsar_request_subject      ON dsar_request (subject_type, subject_id);
-- Index for the export job claim sweep (VERIFIED requests, ordered by submitted_at)
CREATE INDEX idx_dsar_request_verified     ON dsar_request (state, submitted_at)
    WHERE state = 'VERIFIED';

-- ── dsar_export_artifact ──────────────────────────────────────────────────────
CREATE TABLE dsar_export_artifact (
    id               UUID         NOT NULL,
    dsar_request_id  UUID         NOT NULL REFERENCES dsar_request(id),
    storage_key      VARCHAR(500) NOT NULL,
    manifest         JSONB        NOT NULL DEFAULT '[]',
    export_data      TEXT,
    byte_size        BIGINT,
    generated_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disposed_at      TIMESTAMPTZ,
    version          INTEGER      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_dsar_export_artifact PRIMARY KEY (id),
    CONSTRAINT uq_dsar_artifact_request UNIQUE (dsar_request_id)
);

-- ── Envers AUD tables ─────────────────────────────────────────────────────────
CREATE TABLE dsar_request_aud (
    id                   UUID         NOT NULL,
    rev                  INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype              SMALLINT,
    request_type         VARCHAR(20),
    subject_type         VARCHAR(50),
    subject_id           UUID,
    submitted_at         TIMESTAMPTZ,
    due_at               TIMESTAMPTZ,
    identity_verified_at TIMESTAMPTZ,
    verification_method  VARCHAR(30),
    state                VARCHAR(20),
    assigned_handler     UUID,
    outcome              VARCHAR(30),
    outcome_note         TEXT,
    attempt_count        INTEGER,
    notes                TEXT,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,

    CONSTRAINT pk_dsar_request_aud PRIMARY KEY (id, rev)
);

CREATE TABLE dsar_export_artifact_aud (
    id               UUID         NOT NULL,
    rev              INTEGER      NOT NULL REFERENCES revinfo(rev),
    revtype          SMALLINT,
    dsar_request_id  UUID,
    storage_key      VARCHAR(500),
    manifest         JSONB,
    byte_size        BIGINT,
    generated_at     TIMESTAMPTZ,
    disposed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,

    CONSTRAINT pk_dsar_export_artifact_aud PRIMARY KEY (id, rev)
);

-- BRIN indexes on AUD tables (append-only, naturally ordered by rev)
CREATE INDEX idx_dsar_request_aud_rev     ON dsar_request_aud     USING BRIN (rev);
CREATE INDEX idx_dsar_artifact_aud_rev    ON dsar_export_artifact_aud USING BRIN (rev);
