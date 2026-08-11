-- V26__notification_delivery_attempt.sql
-- Stores one row per notification dispatch operation with masked recipient.
-- Unique constraint on (event_id, channel, recipient_user_id) enforces idempotency.
-- Expand-only: no destructive changes.

CREATE TABLE notification_delivery_attempt (
    id                 UUID         NOT NULL,
    event_id           UUID         NOT NULL,
    channel            VARCHAR(20)  NOT NULL,
    adapter            VARCHAR(100) NOT NULL,
    recipient_user_id  UUID         NOT NULL,
    recipient_mask     VARCHAR(255) NOT NULL,
    attempt_no         INT          NOT NULL DEFAULT 1,
    outcome            VARCHAR(30)  NOT NULL,
    provider_reference VARCHAR(255),
    duration_ms        INT          NOT NULL DEFAULT 0,
    failure_code       VARCHAR(100),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_notification_delivery_attempt PRIMARY KEY (id),
    CONSTRAINT chk_nda_channel CHECK (channel IN ('EMAIL','SMS','PUSH','IN_APP')),
    CONSTRAINT chk_nda_outcome CHECK (outcome IN ('SENT','DEGRADED','RETRYABLE_FAILURE','PERMANENT_FAILURE')),
    CONSTRAINT uq_nda_event_channel_user UNIQUE (event_id, channel, recipient_user_id)
);

CREATE INDEX idx_nda_event_id    ON notification_delivery_attempt (event_id);
CREATE INDEX idx_nda_created_at  ON notification_delivery_attempt (created_at DESC);
