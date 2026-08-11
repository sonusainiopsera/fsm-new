-- V27__in_app_notification.sql
-- Durable in-app notifications written by the fallback adapter when the external
-- provider is unreachable or the circuit breaker is open.
-- Expand-only: no destructive changes.

CREATE TABLE in_app_notification (
    id                UUID         NOT NULL,
    recipient_user_id UUID         NOT NULL,
    event_id          UUID         NOT NULL,
    category          VARCHAR(100) NOT NULL,
    title             VARCHAR(500) NOT NULL,
    body              TEXT         NOT NULL,
    severity          VARCHAR(20)  NOT NULL DEFAULT 'INFO',
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_in_app_notification PRIMARY KEY (id),
    CONSTRAINT chk_ian_severity CHECK (severity IN ('INFO','WARNING','CRITICAL'))
);

CREATE INDEX idx_ian_recipient ON in_app_notification (recipient_user_id, created_at DESC);
