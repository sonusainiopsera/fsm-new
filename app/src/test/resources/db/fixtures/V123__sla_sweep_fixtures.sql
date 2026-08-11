-- =============================================================================
-- V123: SLA sweep evaluator fixtures for WO-143 integration tests
-- =============================================================================
-- Inserts three work orders in various SLA risk states:
--
--   WO_SLA_HEALTHY  (7a000000-...-0001) — in_progress, deadline 8 hours ahead → healthy
--   WO_SLA_AT_RISK  (7a000000-...-0002) — in_progress, past at-risk threshold → AT_RISK
--   WO_SLA_OVERRUN  (7a000000-...-0003) — in_progress, past resolution deadline → PROJECTED_OVERRUN
--   WO_SLA_TERMINAL (7a000000-...-0004) — COMPLETED with open flag → flag should be cleared
--
-- Timestamps use absolute values well into the past/future relative to test clock
-- (2026-01-01T12:00:00Z) which tests supply via a fixed Clock override.
--
-- IDs use the 7a000000 prefix to avoid conflicts with all earlier fixtures.
-- =============================================================================

-- WO_SLA_HEALTHY: created 30 min ago; at-risk threshold 192 min from creation (162 min ahead)
INSERT INTO work_order (id, site_id, customer_id, state, priority, title, reference,
                        description, fault_description, sla_deadline, version,
                        created_at, response_due_at, resolution_due_at, at_risk_at)
VALUES (
    '7a000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'IN_PROGRESS', 'HIGH',
    'SLA Sweep Fixture — Healthy',
    'REF-SLA-SWEEP-001',
    'Healthy work order for sweep test.', 'Normal fault.',
    '2026-01-01 16:00:00+00', -- sla_deadline (legacy column, required)
    0,
    '2026-01-01 11:30:00+00',  -- created_at: 30 min before test clock (12:00)
    '2026-01-01 12:30:00+00',  -- response_due_at
    '2026-01-01 15:30:00+00',  -- resolution_due_at: 3.5 hours from test clock
    '2026-01-01 14:42:00+00'   -- at_risk_at: 192 min from creation = 14:42
);

-- WO_SLA_AT_RISK: created 200 min ago; past at-risk threshold (192 min), before deadline
INSERT INTO work_order (id, site_id, customer_id, state, priority, title, reference,
                        description, fault_description, sla_deadline, version,
                        created_at, response_due_at, resolution_due_at, at_risk_at)
VALUES (
    '7a000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'IN_PROGRESS', 'HIGH',
    'SLA Sweep Fixture — At Risk',
    'REF-SLA-SWEEP-002',
    'At-risk work order for sweep test.', 'Fault at risk.',
    '2026-01-01 16:00:00+00',
    0,
    '2026-01-01 08:40:00+00',  -- created_at: 200 min before test clock
    '2026-01-01 09:40:00+00',  -- response_due_at
    '2026-01-01 12:40:00+00',  -- resolution_due_at: 40 min after test clock
    '2026-01-01 11:52:00+00'   -- at_risk_at: 192 min from creation = 11:52 (8 min before test clock)
);

-- WO_SLA_OVERRUN: past resolution deadline
INSERT INTO work_order (id, site_id, customer_id, state, priority, title, reference,
                        description, fault_description, sla_deadline, version,
                        created_at, response_due_at, resolution_due_at, at_risk_at)
VALUES (
    '7a000000-0000-0000-0000-000000000003',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'IN_PROGRESS', 'HIGH',
    'SLA Sweep Fixture — Overrun',
    'REF-SLA-SWEEP-003',
    'Overrun work order for sweep test.', 'Fault overrun.',
    '2026-01-01 10:00:00+00',
    0,
    '2026-01-01 06:00:00+00',  -- created_at: 6 hours before test clock
    '2026-01-01 07:00:00+00',
    '2026-01-01 10:00:00+00',  -- resolution_due_at: 2 hours before test clock (overrun)
    '2026-01-01 09:12:00+00'   -- at_risk_at
);

-- WO_SLA_TERMINAL: COMPLETED — should clear any open flags
INSERT INTO work_order (id, site_id, customer_id, state, priority, title, reference,
                        description, fault_description, sla_deadline, version,
                        created_at, response_due_at, resolution_due_at, at_risk_at)
VALUES (
    '7a000000-0000-0000-0000-000000000004',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    'COMPLETED', 'HIGH',
    'SLA Sweep Fixture — Terminal',
    'REF-SLA-SWEEP-004',
    'Terminal work order for sweep test.', 'Fault resolved.',
    '2026-01-01 10:00:00+00',
    0,
    '2026-01-01 06:00:00+00',
    '2026-01-01 07:00:00+00',
    '2026-01-01 10:00:00+00',
    '2026-01-01 09:12:00+00'
);

-- Pre-existing open AT_RISK flag for the terminal work order (should be cleared by sweep)
INSERT INTO sla_risk_flag (id, work_order_id, flag_type, trigger_reason, projection_basis,
                           minutes_remaining, raised_at, cleared_at, created_by_system, version)
VALUES (
    '7a000000-0000-0000-0000-000000000010',
    '7a000000-0000-0000-0000-000000000004',
    'AT_RISK',
    'at_risk_threshold_elapsed',
    'test fixture pre-existing flag',
    15,
    '2026-01-01 09:30:00+00',
    NULL,  -- open flag
    true,
    0
);
