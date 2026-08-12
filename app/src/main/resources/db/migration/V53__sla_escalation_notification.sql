-- WO-146: SLA escalation notification audit table and manager escalation marker
-- Every escalation send attempt is recorded with its outcome.
-- Unique index on (event_id, recipient_user_id, channel) enforces idempotent delivery.

CREATE TABLE sla_escalation_notification (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    event_id            UUID        NOT NULL,
    work_order_id       UUID        NOT NULL,
    recipient_user_id   UUID        NOT NULL,
    recipient_role      VARCHAR(20) NOT NULL,
    channel             VARCHAR(10) NOT NULL,
    event_type          VARCHAR(50) NOT NULL,
    attempt_count       INT         NOT NULL DEFAULT 1,
    outcome             VARCHAR(20) NOT NULL,
    outcome_reason      VARCHAR(200),
    provider_message_id VARCHAR(255),
    masked_destination  VARCHAR(100) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_sla_escalation_notification     PRIMARY KEY (id),
    CONSTRAINT chk_sla_escalation_notification_outcome
        CHECK (outcome IN ('SENT','FAILED','DEGRADED','SKIPPED','SUPPRESSED'))
);

-- Idempotency: one row per (event, recipient, channel)
CREATE UNIQUE INDEX uix_sla_escalation_event_recipient_channel
    ON sla_escalation_notification (event_id, recipient_user_id, channel);

-- Suppression window query: work order × recipient × channel
CREATE INDEX idx_sla_escalation_wo_recipient_channel
    ON sla_escalation_notification (work_order_id, recipient_user_id, channel, created_at DESC);

-- Manager one-shot escalation marker on the risk flag row
-- NULL = not yet escalated; non-NULL = escalation was sent at this instant
ALTER TABLE sla_risk_flag
    ADD COLUMN IF NOT EXISTS manager_escalated_at TIMESTAMPTZ;
