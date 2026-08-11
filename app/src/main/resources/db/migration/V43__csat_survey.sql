-- =============================================================================
-- V43: CSAT survey issuance and response capture (WO-173)
--
-- csat_survey: one row per closed work order; uniqueness on (work_order_id) and
--   (source_event_id) guarantees exactly-one issuance under at-least-once delivery.
-- csat_response: one row per answered survey; uniqueness on survey_id enforces the
--   single-response constraint at the database level.
-- comment_enc: TEXT storing AES-256-GCM ciphertext (iv.ciphertext Base64 format)
--   produced by EncryptedStringConverter; null when customer omits the comment.
--   Classified Confidential per BR-23, covered by the 12-month retention schedule.
-- Envers audit tables mirror all business columns so revisions are protected by
--   the same field-encryption key.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- csat_survey
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS csat_survey (
    id               UUID         NOT NULL,
    work_order_id    UUID         NOT NULL,
    account_id       UUID         NOT NULL REFERENCES customer (id),
    source_event_id  UUID         NOT NULL,
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'ANSWERED', 'EXPIRED')),
    delivery_status  VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                         CHECK (delivery_status IN ('PENDING', 'IN_APP', 'SENT', 'FAILED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_csat_survey         PRIMARY KEY (id),
    CONSTRAINT uq_csat_survey_wo      UNIQUE (work_order_id),
    CONSTRAINT uq_csat_survey_event   UNIQUE (source_event_id),
    CONSTRAINT fk_csat_survey_wo      FOREIGN KEY (work_order_id) REFERENCES work_order (id)
);

-- Supports portal list queries scoped by account_id + sorted by issued_at DESC
CREATE INDEX IF NOT EXISTS idx_csat_survey_account_issued
    ON csat_survey (account_id, issued_at DESC);

-- ---------------------------------------------------------------------------
-- csat_response
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS csat_response (
    id           UUID        NOT NULL,
    survey_id    UUID        NOT NULL,
    score        SMALLINT    NOT NULL CHECK (score BETWEEN 1 AND 5),
    nps_score    SMALLINT             CHECK (nps_score BETWEEN 0 AND 10),
    comment_enc  TEXT,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT pk_csat_response          PRIMARY KEY (id),
    CONSTRAINT uq_csat_response_survey   UNIQUE (survey_id),
    CONSTRAINT fk_csat_response_survey   FOREIGN KEY (survey_id) REFERENCES csat_survey (id)
);

-- Supports rolling 90-day aggregation
CREATE INDEX IF NOT EXISTS idx_csat_response_submitted_at
    ON csat_response (submitted_at DESC);

-- ---------------------------------------------------------------------------
-- Envers audit tables
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS csat_survey_aud (
    id               UUID        NOT NULL,
    rev              INTEGER     NOT NULL,
    revtype          SMALLINT    NOT NULL,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    work_order_id    UUID,
    account_id       UUID,
    source_event_id  UUID,
    issued_at        TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    status           VARCHAR(10),
    delivery_status  VARCHAR(10),
    CONSTRAINT pk_csat_survey_aud  PRIMARY KEY (id, rev),
    CONSTRAINT fk_csat_survey_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE TABLE IF NOT EXISTS csat_response_aud (
    id           UUID        NOT NULL,
    rev          INTEGER     NOT NULL,
    revtype      SMALLINT    NOT NULL,
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    survey_id    UUID,
    score        SMALLINT,
    nps_score    SMALLINT,
    comment_enc  TEXT,
    submitted_at TIMESTAMPTZ,
    CONSTRAINT pk_csat_response_aud  PRIMARY KEY (id, rev),
    CONSTRAINT fk_csat_response_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ---------------------------------------------------------------------------
-- Retention policy: CSAT rows are Confidential (comment_enc is customer PII)
-- Anchor: csat_survey.issued_at, 12 months retention (BR-25)
-- ---------------------------------------------------------------------------
INSERT INTO retention_policy
    (id, data_category, entity_name, period_value, period_unit, anchor_field,
     disposal_method, legal_hold, ratified, enabled, notes, created_at, updated_at, version)
VALUES
    (gen_random_uuid(),
     'CSAT_SURVEY',
     'csat_survey',
     12, 'MONTHS',
     'issued_at',
     'PHYSICAL_DELETE',
     false, false, false,
     'CSAT survey rows including survey_id FK in csat_response; '
     'purge both tables together. comment_enc classified Confidential (BR-23).',
     now(), now(), 0)
ON CONFLICT DO NOTHING;
