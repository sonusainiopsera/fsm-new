-- V107__hold_fixtures.sql
-- Test fixtures for hold reason vocabulary and hold interval tests (WO-126).
--
-- Fixture topology:
--   hold_reason rows: AWAITING_PARTS (active), WEATHER (active), INACTIVE_CODE (inactive)
--
--   Work orders for hold tests (IDs: 3b000000-...):
--     wo_hold_no_holds        (3b000000-...-000000000001): IN_PROGRESS, no holds
--     wo_hold_open_hold       (3b000000-...-000000000002): ON_HOLD, one open hold (AWAITING_PARTS)
--     wo_hold_closed_holds    (3b000000-...-000000000003): IN_PROGRESS, two closed holds (60 + 30 = 90 min cumulative)
--
--   hold records (IDs: 4b000000-...):
--     open hold on wo_hold_open_hold
--     two closed holds on wo_hold_closed_holds

-- =============================================================================
-- hold_reason: seed an inactive code for testing rejection paths
-- (Active vocabulary was seeded by V19__hold_reason.sql)
-- =============================================================================
INSERT INTO hold_reason (code, label, active, sort_order) VALUES
    ('INACTIVE_TEST_CODE', 'Inactive code for testing only', false, 999)
ON CONFLICT (code) DO NOTHING;

-- =============================================================================
-- Work orders for hold tests
-- =============================================================================
INSERT INTO work_order
    (id, site_id, customer_id, assigned_technician_id, state, priority, description, version, cumulative_hold_minutes)
VALUES
    -- IN_PROGRESS, no holds at all
    ('3b000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'IN_PROGRESS', 'MEDIUM', 'Hold test: no holds', 0, 0),
    -- ON_HOLD, one open hold
    ('3b000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'ON_HOLD', 'MEDIUM', 'Hold test: open hold', 0, 0),
    -- IN_PROGRESS, two closed holds totalling 90 minutes
    ('3b000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'IN_PROGRESS', 'MEDIUM', 'Hold test: two closed holds', 0, 90);

-- =============================================================================
-- Labour time for hold test work orders so COMPLETE guard passes
-- =============================================================================
INSERT INTO labour_time_record (id, work_order_id, technician_id, minutes, work_date, created_at)
VALUES
    ('4b000000-0000-0000-0000-000000000010',
     '3b000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     60, now(), now()),
    ('4b000000-0000-0000-0000-000000000011',
     '3b000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000011',
     60, now(), now()),
    ('4b000000-0000-0000-0000-000000000012',
     '3b000000-0000-0000-0000-000000000003',
     '00000000-0000-0000-0000-000000000011',
     60, now(), now());

-- =============================================================================
-- Hold records
-- =============================================================================
-- Open hold on wo_hold_open_hold
INSERT INTO work_order_hold
    (id, work_order_id, reason_code, note, started_at, ended_at, started_by, ended_by)
VALUES
    ('4b000000-0000-0000-0000-000000000001',
     '3b000000-0000-0000-0000-000000000002',
     'AWAITING_PARTS',
     'Waiting for compressor unit',
     now() - INTERVAL '30 minutes',
     NULL,
     '00000000-0000-0000-0000-000000000001',
     NULL);

-- Two closed holds on wo_hold_closed_holds (60 + 30 = 90 min)
INSERT INTO work_order_hold
    (id, work_order_id, reason_code, note, started_at, ended_at, started_by, ended_by)
VALUES
    ('4b000000-0000-0000-0000-000000000002',
     '3b000000-0000-0000-0000-000000000003',
     'AWAITING_PARTS',
     NULL,
     now() - INTERVAL '3 hours',
     now() - INTERVAL '3 hours' + INTERVAL '60 minutes',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001'),
    ('4b000000-0000-0000-0000-000000000003',
     '3b000000-0000-0000-0000-000000000003',
     'WEATHER',
     'Rain prevented outdoor work',
     now() - INTERVAL '1 hour',
     now() - INTERVAL '30 minutes',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001');
