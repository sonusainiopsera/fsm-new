-- WO-146: SLA escalation notification tables and manager-tier escalation marker

-- ─── sla_escalation_policy ─────────────────────────────────────────────────────────
-- Data-driven policy rows keyed by event_type + priority.
-- recipient_roles is a comma-separated list of AppRole names (e.g. 'DISPATCHER,MANAGER').
-- channels is a comma-separated list of NotificationChannel names (e.g. 'EMAIL').
-- Quiet-hours are evaluated in UTC unless zone is set (Java ZoneId string).

CREATE TABLE sla_escalation_policy (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    event_type              VARCHAR(60)     NOT NULL,
    priority                VARCHAR(30)     NOT NULL DEFAULT '*',
    recipient_roles         VARCHAR(255)    NOT NULL DEFAULT 'DISPATCHER',
    channels                VARCHAR(100)    NOT NULL DEFAULT 'EMAIL',
    manager_grace_minutes   INTEGER         NOT NULL DEFAULT 30,
    quiet_hours_start       VARCHAR(5),
    quiet_hours_end         VARCHAR(5),
    zone                    VARCHAR(60)     NOT NULL DEFAULT 'UTC',
    active                  BOOLEAN         NOT NULL DEFAULT TRUE,
    effective_from          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    effective_to            TIMESTAMPTZ,
    dedup_window_minutes    INTEGER         NOT NULL DEFAULT 60,
    CONSTRAINT pk_sla_escalation_policy PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_sla_escalation_policy_active
    ON sla_escalation_policy (event_type, priority)
    WHERE active = TRUE AND effective_to IS NULL;

-- Seed default policies
INSERT INTO sla_escalation_policy
    (event_type, priority, recipient_roles, channels, manager_grace_minutes,
     quiet_hours_start, quiet_hours_end, zone, dedup_window_minutes)
VALUES
    ('SlaRiskFlagged', '*', 'DISPATCHER', 'EMAIL', 30, '22:00', '07:00', 'UTC', 60),
    ('SlaBreached',    '*', 'DISPATCHER,MANAGER', 'EMAIL', 0, NULL, NULL, 'UTC', 0)
ON CONFLICT DO NOTHING;

-- ─── sla_escalation_notification ──────────────────────────────────────────────────
-- One row per (event_id, recipient_user_id, channel) attempt.
-- outcome values: SENT, FAILED, DEGRADED, SKIPPED, SUPPRESSED.

CREATE TABLE sla_escalation_notification (
    id                  UUID            NOT NULL,
    event_id            UUID            NOT NULL,
    work_order_id       UUID            NOT NULL,
    recipient_user_id   UUID            NOT NULL,
    recipient_role      VARCHAR(50)     NOT NULL,
    channel             VARCHAR(20)     NOT NULL,
    attempt_count       INTEGER         NOT NULL DEFAULT 1,
    outcome             VARCHAR(20)     NOT NULL
        CONSTRAINT chk_escalation_outcome
            CHECK (outcome IN ('SENT','FAILED','DEGRADED','SKIPPED','SUPPRESSED')),
    outcome_reason      VARCHAR(100),
    provider_message_id VARCHAR(255),
    masked_destination  VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_sla_escalation_notification PRIMARY KEY (id)
);

-- Idempotency: same event + recipient + channel never produces a second row
CREATE UNIQUE INDEX uq_sla_escalation_notification_idempotency
    ON sla_escalation_notification (event_id, recipient_user_id, channel);

-- Suppression window query index
CREATE INDEX idx_sla_escalation_notification_wo_recipient
    ON sla_escalation_notification (work_order_id, recipient_user_id, channel, created_at);

-- ─── manager_escalated_at on sla_risk_flag ─────────────────────────────────────────
-- One-shot marker: set when the manager tier has been escalated for this flag.
-- NULL = not yet escalated; non-null = already sent.
ALTER TABLE sla_risk_flag
    ADD COLUMN IF NOT EXISTS manager_escalated_at TIMESTAMPTZ;
