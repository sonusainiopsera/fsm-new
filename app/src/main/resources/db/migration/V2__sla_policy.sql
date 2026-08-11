-- V2__sla_policy.sql
-- SLA policy configuration table with seeded placeholder rows.
-- Downstream deadline derivation must query this table; no code may
-- hard-code priority deadlines. An absent or inactive row for a priority
-- must be detected explicitly and fail the deadline derivation — never default silently.

CREATE TABLE sla_policy (
    id                  UUID            NOT NULL PRIMARY KEY,
    priority            VARCHAR(10)     NOT NULL,
    response_minutes    INTEGER         NOT NULL,  -- minutes to first technician response
    resolution_minutes  INTEGER         NOT NULL,  -- minutes to work order completion
    at_risk_fraction    NUMERIC(3,2)    NOT NULL DEFAULT 0.80,
    effective_from      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    effective_to        TIMESTAMPTZ,               -- null = currently active
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_sla_policy_priority CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT chk_sla_policy_response_positive    CHECK (response_minutes > 0),
    CONSTRAINT chk_sla_policy_resolution_positive  CHECK (resolution_minutes > 0),
    CONSTRAINT chk_sla_policy_at_risk_fraction     CHECK (at_risk_fraction > 0 AND at_risk_fraction < 1),
    CONSTRAINT uq_sla_policy_priority_effective    UNIQUE (priority, effective_from)
);

-- Seed placeholder SLA rows so no code path depends on unratified priority tier values.
-- These are placeholders — operations team will override via the admin UI.
-- effective_from = epoch so they sort before any real policy row.
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction, effective_from)
VALUES
    ('00000001-0000-7000-8000-000000000001', 'LOW',      480,  2880,  0.80, '2000-01-01 00:00:00+00'),
    ('00000001-0000-7000-8000-000000000002', 'MEDIUM',   240,  1440,  0.80, '2000-01-01 00:00:00+00'),
    ('00000001-0000-7000-8000-000000000003', 'HIGH',      60,   480,  0.80, '2000-01-01 00:00:00+00'),
    ('00000001-0000-7000-8000-000000000004', 'CRITICAL',  15,   120,  0.80, '2000-01-01 00:00:00+00');
