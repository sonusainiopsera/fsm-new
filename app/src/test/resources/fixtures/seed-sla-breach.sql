-- Seed data for SlaBreachIT integration tests.
--
-- All UUIDs use prefix 00000000-0000-7144 (WO-144) to allow scoped assertions.
-- Scenarios covered:
--   1. Response-only breach (unattributed) — work order still open
--   2. Resolution breach (attributed) — work order still open
--   3. Near-breach saved by pause time — no breach row expected
--   4. Breach with final_overrun_minutes set (work order closed)
--   5. Unattributed resolution breach for list/filter tests

-- Work orders referenced by breach records
INSERT INTO work_order (id, title, description, state, priority, created_at, updated_at, version)
VALUES
    ('00000000-0000-7144-0001-000000000001', 'WO-144 Response breach WO',   'Test WO', 'IN_PROGRESS', 'P1', NOW() - INTERVAL '4 hours', NOW(), 0),
    ('00000000-0000-7144-0001-000000000002', 'WO-144 Resolution breach WO', 'Test WO', 'IN_PROGRESS', 'P2', NOW() - INTERVAL '8 hours', NOW(), 0),
    ('00000000-0000-7144-0001-000000000003', 'WO-144 Near-breach WO',       'Test WO', 'ON_HOLD',     'P3', NOW() - INTERVAL '6 hours', NOW(), 0),
    ('00000000-0000-7144-0001-000000000004', 'WO-144 Closed breached WO',   'Test WO', 'CLOSED',      'P1', NOW() - INTERVAL '24 hours', NOW(), 0),
    ('00000000-0000-7144-0001-000000000005', 'WO-144 Unattributed WO',      'Test WO', 'IN_PROGRESS', 'P2', NOW() - INTERVAL '10 hours', NOW(), 0)
ON CONFLICT (id) DO NOTHING;

-- Scenario 1: Response-only breach, unattributed
INSERT INTO sla_breach (id, work_order_id, breach_type, effective_deadline, detected_at,
                         overrun_minutes, paused_minutes_excluded, final_overrun_minutes,
                         reason_code, reason_note, attributed_by, attributed_at, version)
VALUES (
    '00000000-0000-7144-0002-000000000001',
    '00000000-0000-7144-0001-000000000001',
    'RESPONSE',
    NOW() - INTERVAL '90 minutes',
    NOW() - INTERVAL '30 minutes',
    30,    -- overrun at detection
    0,
    NULL,  -- not yet finalised
    NULL,  -- unattributed
    NULL,
    NULL,
    NULL,
    0
) ON CONFLICT (id) DO NOTHING;

-- Scenario 2: Resolution breach, attributed
INSERT INTO sla_breach (id, work_order_id, breach_type, effective_deadline, detected_at,
                         overrun_minutes, paused_minutes_excluded, final_overrun_minutes,
                         reason_code, reason_note, attributed_by, attributed_at, version)
VALUES (
    '00000000-0000-7144-0002-000000000002',
    '00000000-0000-7144-0001-000000000002',
    'RESOLUTION',
    NOW() - INTERVAL '3 hours',
    NOW() - INTERVAL '1 hour',
    120,   -- overrun at detection (2 h)
    30,    -- 30 min of pauses excluded
    NULL,
    'PARTS_UNAVAILABLE',
    'Critical parts on back-order from supplier',
    '00000000-0000-0000-0000-000000000099',
    NOW() - INTERVAL '45 minutes',
    1      -- one attribution revision
) ON CONFLICT (id) DO NOTHING;

-- Scenario 3: No breach row — near-breach WO was paused before deadline
-- (no INSERT into sla_breach — verifies no spurious row)

-- Scenario 4: Breach with final_overrun_minutes set (WO closed after breach)
INSERT INTO sla_breach (id, work_order_id, breach_type, effective_deadline, detected_at,
                         overrun_minutes, paused_minutes_excluded, final_overrun_minutes,
                         reason_code, reason_note, attributed_by, attributed_at, version)
VALUES (
    '00000000-0000-7144-0002-000000000004',
    '00000000-0000-7144-0001-000000000004',
    'RESOLUTION',
    NOW() - INTERVAL '20 hours',
    NOW() - INTERVAL '18 hours',
    120,   -- overrun at detection
    0,
    360,   -- final overrun: 6 hours (written at closure)
    'CAPACITY_SHORTFALL',
    'Team capacity was insufficient for P1 volume',
    '00000000-0000-0000-0000-000000000099',
    NOW() - INTERVAL '2 hours',
    2
) ON CONFLICT (id) DO NOTHING;

-- Scenario 5: Unattributed resolution breach for list/filter tests
INSERT INTO sla_breach (id, work_order_id, breach_type, effective_deadline, detected_at,
                         overrun_minutes, paused_minutes_excluded, final_overrun_minutes,
                         reason_code, reason_note, attributed_by, attributed_at, version)
VALUES (
    '00000000-0000-7144-0002-000000000005',
    '00000000-0000-7144-0001-000000000005',
    'RESOLUTION',
    NOW() - INTERVAL '5 hours',
    NOW() - INTERVAL '4 hours',
    60,
    0,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    0
) ON CONFLICT (id) DO NOTHING;
