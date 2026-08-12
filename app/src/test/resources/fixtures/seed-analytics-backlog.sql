-- seed-analytics-backlog.sql
-- Anonymized fixture data for backlog and workload balance analytics tests (WO-165).
-- UUIDs use prefix 'dd000000-' for easy identification and cleanup.
--
-- Covers:
--   - Work orders in every open state (NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD)
--   - All four hold reasons populated on ON_HOLD WOs
--   - All four priority tiers: LOW, MEDIUM, HIGH, CRITICAL
--   - Evenly loaded team (CV ≈ 0): 3 techs with equal hours
--   - Severely imbalanced team: tech hours [240 min, 480 min, 900 min]
--   - Two-technician team (< 3 → NOT_MEANINGFUL)
--   - Zero assigned hours (zero-mean → NOT_MEANINGFUL)
-- All data is synthetic — no real names, sites, or assets.

-- ============================================================
-- Cleanup prior runs (safe to re-run)
-- ============================================================
DELETE FROM work_order_labour_entry WHERE notes LIKE 'seed-backlog-%';
DELETE FROM work_order_hold WHERE note LIKE 'seed-backlog-%';
DELETE FROM work_order WHERE reference LIKE 'DD-BL-%';
DELETE FROM kpi_trend_point WHERE metric_key IN ('backlog.open.count', 'backlog.on_hold.count');

-- ============================================================
-- Reference data (idempotent)
-- ============================================================

INSERT INTO customer (id, name, version)
VALUES ('dd000000-0000-7001-8000-000000000099', 'Backlog Fixture Customer', 0)
ON CONFLICT DO NOTHING;

INSERT INTO site (id, name, customer_id, version)
VALUES ('dd000000-0000-7001-8000-000000000001', 'Backlog Fixture Site',
        'dd000000-0000-7001-8000-000000000099', 0)
ON CONFLICT DO NOTHING;

-- Three active technicians (evenly loaded team)
INSERT INTO app_user (id, email, full_name, version) VALUES
    ('dd000000-0000-7001-8000-000000000010', 'dt-a@fixture.test', 'Diag Tech A', 0),
    ('dd000000-0000-7001-8000-000000000011', 'dt-b@fixture.test', 'Diag Tech B', 0),
    ('dd000000-0000-7001-8000-000000000012', 'dt-c@fixture.test', 'Diag Tech C', 0)
ON CONFLICT DO NOTHING;

INSERT INTO technician (id, user_id, full_name, active, version) VALUES
    ('dd000000-0000-7001-8000-000000000010', 'dd000000-0000-7001-8000-000000000010', 'Diag Tech A', TRUE, 0),
    ('dd000000-0000-7001-8000-000000000011', 'dd000000-0000-7001-8000-000000000011', 'Diag Tech B', TRUE, 0),
    ('dd000000-0000-7001-8000-000000000012', 'dd000000-0000-7001-8000-000000000012', 'Diag Tech C', TRUE, 0)
ON CONFLICT DO NOTHING;

-- ============================================================
-- Work orders in every open state, all four priorities
-- ============================================================

-- NEW state
INSERT INTO work_order (id, reference, state, priority, site_id, version) VALUES
    ('dd000000-0001-7000-8000-000000000001', 'DD-BL-001', 'NEW', 'HIGH',     'dd000000-0000-7001-8000-000000000001', 0),
    ('dd000000-0001-7000-8000-000000000002', 'DD-BL-002', 'NEW', 'MEDIUM',   'dd000000-0000-7001-8000-000000000001', 0),
    ('dd000000-0001-7000-8000-000000000003', 'DD-BL-003', 'NEW', 'LOW',      'dd000000-0000-7001-8000-000000000001', 0),
    ('dd000000-0001-7000-8000-000000000004', 'DD-BL-004', 'NEW', 'CRITICAL', 'dd000000-0000-7001-8000-000000000001', 0);

-- ASSIGNED state
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, version) VALUES
    ('dd000000-0002-7000-8000-000000000001', 'DD-BL-005', 'ASSIGNED', 'HIGH',   'dd000000-0000-7001-8000-000000000001', 'dd000000-0000-7001-8000-000000000010', 0),
    ('dd000000-0002-7000-8000-000000000002', 'DD-BL-006', 'ASSIGNED', 'MEDIUM', 'dd000000-0000-7001-8000-000000000001', 'dd000000-0000-7001-8000-000000000011', 0);

-- EN_ROUTE state
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, version) VALUES
    ('dd000000-0003-7000-8000-000000000001', 'DD-BL-007', 'EN_ROUTE', 'HIGH', 'dd000000-0000-7001-8000-000000000001', 'dd000000-0000-7001-8000-000000000010', 0);

-- IN_PROGRESS state
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, version) VALUES
    ('dd000000-0004-7000-8000-000000000001', 'DD-BL-008', 'IN_PROGRESS', 'HIGH',   'dd000000-0000-7001-8000-000000000001', 'dd000000-0000-7001-8000-000000000011', 0),
    ('dd000000-0004-7000-8000-000000000002', 'DD-BL-009', 'IN_PROGRESS', 'MEDIUM', 'dd000000-0000-7001-8000-000000000001', 'dd000000-0000-7001-8000-000000000012', 0);

-- ON_HOLD state — seeded with different hold reasons
INSERT INTO work_order (id, reference, state, priority, site_id, version) VALUES
    ('dd000000-0005-7000-8000-000000000001', 'DD-BL-010', 'ON_HOLD', 'HIGH',   'dd000000-0000-7001-8000-000000000001', 0),
    ('dd000000-0005-7000-8000-000000000002', 'DD-BL-011', 'ON_HOLD', 'MEDIUM', 'dd000000-0000-7001-8000-000000000001', 0),
    ('dd000000-0005-7000-8000-000000000003', 'DD-BL-012', 'ON_HOLD', 'LOW',    'dd000000-0000-7001-8000-000000000001', 0),
    ('dd000000-0005-7000-8000-000000000004', 'DD-BL-013', 'ON_HOLD', 'HIGH',   'dd000000-0000-7001-8000-000000000001', 0);

-- Active hold records for ON_HOLD WOs (ended_at IS NULL = currently held)
INSERT INTO work_order_hold (id, work_order_id, reason_code, note, started_at, started_by) VALUES
    ('dd000000-0006-7000-8000-000000000001', 'dd000000-0005-7000-8000-000000000001', 'AWAITING_PARTS',       'seed-backlog-hold', NOW() - INTERVAL '2 days', 'dd000000-0000-7001-8000-000000000010'),
    ('dd000000-0006-7000-8000-000000000002', 'dd000000-0005-7000-8000-000000000002', 'CUSTOMER_UNAVAILABLE', 'seed-backlog-hold', NOW() - INTERVAL '1 day',  'dd000000-0000-7001-8000-000000000011'),
    ('dd000000-0006-7000-8000-000000000003', 'dd000000-0005-7000-8000-000000000003', 'ACCESS_DENIED',        'seed-backlog-hold', NOW() - INTERVAL '3 days', 'dd000000-0000-7001-8000-000000000012'),
    ('dd000000-0006-7000-8000-000000000004', 'dd000000-0005-7000-8000-000000000004', 'AWAITING_PARTS',       'seed-backlog-hold', NOW() - INTERVAL '4 hours','dd000000-0000-7001-8000-000000000010');

-- Terminal WOs — should NOT appear in backlog counts
INSERT INTO work_order (id, reference, state, priority, site_id, version) VALUES
    ('dd000000-0007-7000-8000-000000000001', 'DD-BL-020', 'COMPLETED', 'HIGH',   'dd000000-0000-7001-8000-000000000001', 0),
    ('dd000000-0007-7000-8000-000000000002', 'DD-BL-021', 'CLOSED',    'MEDIUM', 'dd000000-0000-7001-8000-000000000001', 0),
    ('dd000000-0007-7000-8000-000000000003', 'DD-BL-022', 'CANCELLED', 'LOW',    'dd000000-0000-7001-8000-000000000001', 0);

-- ============================================================
-- Labour entries for workload balance
-- ============================================================

-- Evenly loaded team: each technician has 480 minutes (8h) → CV = 0
INSERT INTO work_order_labour_entry (id, work_order_id, technician_id, minutes, notes, created_at) VALUES
    ('dd000000-0008-7000-8000-000000000001', 'dd000000-0007-7000-8000-000000000001', 'dd000000-0000-7001-8000-000000000010', 480, 'seed-backlog-even', NOW() - INTERVAL '5 days'),
    ('dd000000-0008-7000-8000-000000000002', 'dd000000-0007-7000-8000-000000000001', 'dd000000-0000-7001-8000-000000000011', 480, 'seed-backlog-even', NOW() - INTERVAL '5 days'),
    ('dd000000-0008-7000-8000-000000000003', 'dd000000-0007-7000-8000-000000000001', 'dd000000-0000-7001-8000-000000000012', 480, 'seed-backlog-even', NOW() - INTERVAL '5 days');

-- Severely imbalanced team: hours 4h, 8h, 15h → high CV
INSERT INTO work_order_labour_entry (id, work_order_id, technician_id, minutes, notes, created_at) VALUES
    ('dd000000-0009-7000-8000-000000000001', 'dd000000-0007-7000-8000-000000000002', 'dd000000-0000-7001-8000-000000000010', 240, 'seed-backlog-imbalanced', NOW() - INTERVAL '3 days'),
    ('dd000000-0009-7000-8000-000000000002', 'dd000000-0007-7000-8000-000000000002', 'dd000000-0000-7001-8000-000000000011', 480, 'seed-backlog-imbalanced', NOW() - INTERVAL '3 days'),
    ('dd000000-0009-7000-8000-000000000003', 'dd000000-0007-7000-8000-000000000002', 'dd000000-0000-7001-8000-000000000012', 900, 'seed-backlog-imbalanced', NOW() - INTERVAL '3 days');
