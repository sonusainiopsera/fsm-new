-- WO-146: Data-driven SLA escalation policy table
-- Rows keyed by event_type + priority define recipient roles, channels,
-- quiet-hours window (UTC hour range), and manager grace-period minutes.

CREATE TABLE sla_escalation_policy (
    id                    UUID        NOT NULL DEFAULT gen_random_uuid(),
    event_type            VARCHAR(50) NOT NULL,
    priority              VARCHAR(10) NOT NULL,
    recipient_roles       TEXT[]      NOT NULL DEFAULT '{}',
    channels              TEXT[]      NOT NULL DEFAULT '{EMAIL}',
    manager_grace_minutes INT         NOT NULL DEFAULT 30,
    -- quiet_hours: suppress non-breach notifications between start and end UTC hour
    -- NULL = no quiet hours configured
    quiet_hours_start     INT,
    quiet_hours_end       INT,
    quiet_hours_zone      VARCHAR(50) NOT NULL DEFAULT 'UTC',
    active                BOOLEAN     NOT NULL DEFAULT TRUE,
    effective_from        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    effective_to          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_sla_escalation_policy PRIMARY KEY (id),
    CONSTRAINT chk_sla_escalation_quiet_start
        CHECK (quiet_hours_start IS NULL OR (quiet_hours_start >= 0 AND quiet_hours_start < 24)),
    CONSTRAINT chk_sla_escalation_quiet_end
        CHECK (quiet_hours_end IS NULL OR (quiet_hours_end >= 0 AND quiet_hours_end < 24))
);

CREATE INDEX idx_sla_escalation_policy_lookup
    ON sla_escalation_policy (event_type, priority, active, effective_from DESC);

-- Default policies:
-- SlaRiskFlagged: notify DISPATCHER with quiet-hours for non-CRITICAL
-- SlaBreached:    notify DISPATCHER + MANAGER, no quiet-hours suppression
INSERT INTO sla_escalation_policy
    (event_type, priority, recipient_roles, channels, manager_grace_minutes,
     quiet_hours_start, quiet_hours_end, quiet_hours_zone)
VALUES
    ('SlaRiskFlagged', 'CRITICAL', '{DISPATCHER}',          '{EMAIL}', 15,  NULL, NULL, 'UTC'),
    ('SlaRiskFlagged', 'HIGH',     '{DISPATCHER}',          '{EMAIL}', 30,  22,   6,    'UTC'),
    ('SlaRiskFlagged', 'MEDIUM',   '{DISPATCHER}',          '{EMAIL}', 60,  22,   6,    'UTC'),
    ('SlaRiskFlagged', 'LOW',      '{DISPATCHER}',          '{EMAIL}', 120, 22,   6,    'UTC'),
    ('SlaBreached',    'CRITICAL', '{DISPATCHER,MANAGER}',  '{EMAIL}', 0,   NULL, NULL, 'UTC'),
    ('SlaBreached',    'HIGH',     '{DISPATCHER,MANAGER}',  '{EMAIL}', 0,   NULL, NULL, 'UTC'),
    ('SlaBreached',    'MEDIUM',   '{DISPATCHER,MANAGER}',  '{EMAIL}', 0,   NULL, NULL, 'UTC'),
    ('SlaBreached',    'LOW',      '{DISPATCHER,MANAGER}',  '{EMAIL}', 0,   NULL, NULL, 'UTC');
