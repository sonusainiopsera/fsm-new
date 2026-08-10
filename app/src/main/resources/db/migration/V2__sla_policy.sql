-- =============================================================================
-- V2: SLA policy table — runtime-configurable per-priority response targets
-- =============================================================================

CREATE TABLE sla_policy (
    id                  UUID            PRIMARY KEY,
    priority            VARCHAR(20)     NOT NULL,
    response_minutes    INTEGER         NOT NULL,
    resolution_minutes  INTEGER         NOT NULL,
    -- Fraction of resolution_minutes remaining when a work order is considered "at-risk".
    -- Default 0.80 means alert at 80% of resolution window elapsed.
    at_risk_fraction    NUMERIC(3,2)    NOT NULL DEFAULT 0.80,
    effective_from      TIMESTAMPTZ     NOT NULL,
    effective_to        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT chk_sla_at_risk_fraction CHECK (at_risk_fraction BETWEEN 0.0 AND 1.0),
    CONSTRAINT uq_sla_policy_priority_effective_from UNIQUE (priority, effective_from)
);

-- Placeholder rows for all priority tiers so code never executes a lookup
-- that returns zero rows. Values are conservative stubs — real targets are
-- set by the operations team via the administration API once ratified.
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction, effective_from)
VALUES
    ('f1000000-0001-0001-0001-000000000001', 'CRITICAL', 60,   240,  0.80, '2024-01-01 00:00:00+00'),
    ('f1000000-0002-0002-0002-000000000002', 'HIGH',     120,  480,  0.80, '2024-01-01 00:00:00+00'),
    ('f1000000-0003-0003-0003-000000000003', 'MEDIUM',   240,  1440, 0.80, '2024-01-01 00:00:00+00'),
    ('f1000000-0004-0004-0004-000000000004', 'LOW',      480,  2880, 0.80, '2024-01-01 00:00:00+00');
