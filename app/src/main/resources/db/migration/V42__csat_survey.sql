-- V42__csat_survey.sql
-- CSAT survey issuance and response capture (WO-173).
-- Creates csat_survey and csat_response with unique constraints,
-- CHECK constraints on score/NPS ranges, @Audited AUD tables,
-- and rolling-window aggregation indexes.
--
-- Retention category: CSAT_DATA (12 months after relationship end, per BR-25).
-- Registered in the retention schedule table.

-- ============================================================
-- csat_survey
-- One row per closed work order.  Exactly-one invariant enforced
-- by UNIQUE(work_order_id) and UNIQUE(source_event_id).
-- status:           PENDING | ANSWERED | EXPIRED
-- delivery_status:  PENDING | SENT | FAILED | IN_APP
-- ============================================================
CREATE TABLE csat_survey (
    id               UUID         NOT NULL,
    work_order_id    UUID         NOT NULL,
    account_id       UUID         NOT NULL,
    source_event_id  UUID         NOT NULL,
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    delivery_status  VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    version          INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_csat_survey              PRIMARY KEY (id),
    CONSTRAINT uq_csat_survey_work_order   UNIQUE (work_order_id),
    CONSTRAINT uq_csat_survey_source_event UNIQUE (source_event_id),
    CONSTRAINT fk_csat_survey_work_order   FOREIGN KEY (work_order_id)
        REFERENCES work_order(id) ON DELETE RESTRICT,
    CONSTRAINT fk_csat_survey_account      FOREIGN KEY (account_id)
        REFERENCES customer(id) ON DELETE RESTRICT,
    CONSTRAINT ck_csat_survey_status
        CHECK (status IN ('PENDING', 'ANSWERED', 'EXPIRED')),
    CONSTRAINT ck_csat_survey_delivery_status
        CHECK (delivery_status IN ('PENDING', 'SENT', 'FAILED', 'IN_APP'))
);

-- Supports rolling-window aggregate queries (AC-9)
CREATE INDEX idx_csat_survey_account_issued
    ON csat_survey (account_id, issued_at DESC);

-- ============================================================
-- csat_response
-- One row per survey (UNIQUE survey_id enforces 1:1).
-- comment is stored encrypted (AES-256-GCM Base64 via EncryptedStringConverter).
-- score: 1..5   nps_score: 0..10 (nullable)
-- ============================================================
CREATE TABLE csat_response (
    id           UUID         NOT NULL,
    survey_id    UUID         NOT NULL,
    score        SMALLINT     NOT NULL,
    nps_score    SMALLINT,
    comment      VARCHAR(2048),
    submitted_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version      INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_csat_response            PRIMARY KEY (id),
    CONSTRAINT uq_csat_response_survey     UNIQUE (survey_id),
    CONSTRAINT fk_csat_response_survey     FOREIGN KEY (survey_id)
        REFERENCES csat_survey(id) ON DELETE RESTRICT,
    CONSTRAINT ck_csat_response_score
        CHECK (score BETWEEN 1 AND 5),
    CONSTRAINT ck_csat_response_nps
        CHECK (nps_score IS NULL OR nps_score BETWEEN 0 AND 10)
);

-- Supports rolling-window aggregate queries (AC-9)
CREATE INDEX idx_csat_response_submitted_at
    ON csat_response (submitted_at DESC);

-- ============================================================
-- Envers AUD tables
-- (REVINFO and revinfo_seq already exist from V5__audit_tables.sql)
-- ============================================================
CREATE TABLE csat_survey_aud (
    id              UUID        NOT NULL,
    REV             INTEGER     NOT NULL,
    REVTYPE         SMALLINT,
    work_order_id   UUID,
    account_id      UUID,
    source_event_id UUID,
    issued_at       TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    status          VARCHAR(20),
    delivery_status VARCHAR(20),
    version         INTEGER,
    CONSTRAINT pk_csat_survey_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_csat_survey_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO(REV)
);

CREATE TABLE csat_response_aud (
    id           UUID         NOT NULL,
    REV          INTEGER      NOT NULL,
    REVTYPE      SMALLINT,
    survey_id    UUID,
    score        SMALLINT,
    nps_score    SMALLINT,
    comment      VARCHAR(2048),
    submitted_at TIMESTAMPTZ,
    version      INTEGER,
    CONSTRAINT pk_csat_response_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_csat_response_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO(REV)
);

-- ============================================================
-- Retention schedule entry (BR-25)
-- CSAT rows purged 12 months after relationship end.
-- data_category UNIQUE constraint means on replay this is idempotent.
-- ============================================================
INSERT INTO retention_policy
    (id, data_category, entity_name, period_value, period_unit, anchor_field,
     disposal_method, legal_hold, ratified, enabled, notes, created_at)
VALUES
    (gen_random_uuid(), 'CSAT_SURVEY',   'csat_survey',   12, 'MONTHS', 'issued_at',
     'PHYSICAL_DELETE', FALSE, FALSE, FALSE,
     'Customer satisfaction survey — purge 12 months after service relationship end', NOW()),
    (gen_random_uuid(), 'CSAT_RESPONSE', 'csat_response', 12, 'MONTHS', 'submitted_at',
     'PHYSICAL_DELETE', FALSE, FALSE, FALSE,
     'Customer satisfaction response (PII: encrypted comment) — same schedule as survey', NOW())
ON CONFLICT (data_category) DO NOTHING;
