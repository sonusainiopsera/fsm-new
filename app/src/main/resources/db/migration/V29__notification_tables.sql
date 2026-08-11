-- =============================================================================
-- V29: Notification delivery tables (WO-195)
-- =============================================================================
-- Creates notification_delivery_attempt (append-only audit log) and
-- in_app_notification (durable fallback inbox).
--
-- Expand-phase only: no columns dropped, no tables altered destructively.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- notification_delivery_attempt: append-only delivery audit log
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_delivery_attempt (
    id                  UUID            NOT NULL,
    event_id            UUID            NOT NULL,
    channel             VARCHAR(10)     NOT NULL CHECK (channel IN ('EMAIL','SMS','PUSH','IN_APP')),
    adapter             VARCHAR(30)     NOT NULL,
    recipient_user_id   UUID            NOT NULL,
    recipient_mask      VARCHAR(100)    NOT NULL,
    attempt_no          INTEGER         NOT NULL DEFAULT 1,
    outcome             VARCHAR(20)     NOT NULL CHECK (outcome IN ('SENT','DEGRADED','RETRYABLE_FAILURE','PERMANENT_FAILURE')),
    provider_reference  VARCHAR(255),
    duration_ms         INTEGER,
    failure_code        VARCHAR(100),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_delivery_attempt PRIMARY KEY (id)
);

-- Idempotency guard: only one SENT outcome per (event, channel, user). Multiple
-- DEGRADED/RETRYABLE_FAILURE attempts are allowed (different attempt_no values).
CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_sent
    ON notification_delivery_attempt (event_id, channel, recipient_user_id)
    WHERE outcome = 'SENT';

CREATE INDEX IF NOT EXISTS idx_delivery_attempt_event_id
    ON notification_delivery_attempt (event_id);

CREATE INDEX IF NOT EXISTS idx_delivery_attempt_created_at
    ON notification_delivery_attempt (created_at DESC);

-- ---------------------------------------------------------------------------
-- in_app_notification: durable inbox for degraded-fallback messages
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS in_app_notification (
    id                  UUID            NOT NULL,
    recipient_user_id   UUID            NOT NULL,
    event_id            UUID            NOT NULL,
    category            VARCHAR(100),
    title               VARCHAR(255),
    body                TEXT,
    severity            VARCHAR(20),
    read_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_in_app_notification PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_in_app_notification_user_created
    ON in_app_notification (recipient_user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Row-level grants (mirrors pattern from V13__outbox_event.sql)
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT ON notification_delivery_attempt TO fieldservice_app;
GRANT SELECT, INSERT ON in_app_notification TO fieldservice_app;
GRANT UPDATE (read_at) ON in_app_notification TO fieldservice_app;
