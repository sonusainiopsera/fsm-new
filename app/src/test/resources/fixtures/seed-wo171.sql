-- WO-171 test fixtures: portal status API — work orders in various states under Acme account.
--
-- All UUIDs use prefix 00000000-0000-7171-8000-XXXXXXXXXXXX.
-- Acme customer:  00000000-0000-7012-8000-000000000001  (seeded by seed-core.sql)
-- Acme site 1:    00000000-0000-7013-8000-000000000001  (seeded by seed-core.sql)
-- USER_ACME:      00000000-0000-7019-8000-000000000001  (sub claim for portal JWT)
-- USER_BLUE:      00000000-0000-7019-8000-000000000002  (linked to Bluestone, not Acme)
--
-- Work orders:
--   WO171-NEW  (001): state=NEW  — used for happy-path 200 and 304 conditional GET tests
--   WO171-HOLD (002): state=ON_HOLD AWAITING_PARTS — used for hold-reason label test

INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id,
                        at_risk, cumulative_hold_minutes, version, origin)
VALUES
    ('00000000-0000-7171-8000-000000000001', 'WO171-NEW',  'NEW',     'MEDIUM',
     '00000000-0000-7013-8000-000000000001', NULL,
     FALSE, 0, 3, 'PORTAL'),
    ('00000000-0000-7171-8000-000000000002', 'WO171-HOLD', 'ON_HOLD', 'HIGH',
     '00000000-0000-7013-8000-000000000001', NULL,
     FALSE, 30, 2, 'PORTAL')
ON CONFLICT (id) DO NOTHING;

-- Active hold record for WO171-HOLD (reason: AWAITING_PARTS)
INSERT INTO work_order_hold (id, work_order_id, reason_code, started_at, ended_at, started_by)
VALUES
    ('00000000-0000-7171-8000-000000000010',
     '00000000-0000-7171-8000-000000000002',
     'AWAITING_PARTS',
     NOW() - INTERVAL '2 hours',
     NULL,
     '00000000-0000-7019-8000-000000000001')
ON CONFLICT (id) DO NOTHING;
