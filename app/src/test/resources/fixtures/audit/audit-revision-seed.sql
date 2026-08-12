-- audit-revision-seed.sql
-- Synthetic fixtures for audit revision search integration tests (WO-199).
--
-- These rows are inserted directly into the Envers AUD tables and REVINFO to simulate
-- revision history without actually running domain operations in the test.
--
-- UUID prefix convention:
--   00000000-0000-7199-8000-XXXXXXXXXXXX  WO-199 audit fixtures
--
-- REVINFO revision numbers used: 10001, 10002, 10003 (far above real test revision seq)
-- to avoid conflicts with any Envers-generated revisions from test domain operations.

-- ── REVINFO entries ──────────────────────────────────────────────────────────────────────
INSERT INTO REVINFO (REV, REVTSTMP, actor_user_id, actor_role, trace_id, client_ip)
VALUES
    (10001, EXTRACT(EPOCH FROM NOW() - INTERVAL '2 hours') * 1000,
     'dddddddd-0000-0000-0000-000000000011', 'ADMIN',
     '00000000-0000-7199-0001-000000000001', '127.0.0.1'),
    (10002, EXTRACT(EPOCH FROM NOW() - INTERVAL '1 hour') * 1000,
     'dddddddd-0000-0000-0000-000000000010', 'DISPATCHER',
     '00000000-0000-7199-0001-000000000002', '127.0.0.1'),
    (10003, EXTRACT(EPOCH FROM NOW()) * 1000,
     'dddddddd-0000-0000-0000-000000000011', 'ADMIN',
     '00000000-0000-7199-0001-000000000003', '127.0.0.1')
ON CONFLICT (REV) DO NOTHING;

-- ── work_order_aud entries ───────────────────────────────────────────────────────────────
-- Using work order ID from seed-core.sql (00000000-0000-7178-8000-000000000031 is
-- the grounding WO). We use a synthetic WO id in the fixture UUID space.
INSERT INTO work_order_aud (id, REV, REVTYPE, reference, state, priority, site_id,
                            assigned_technician_id, description, created_at, version)
VALUES
    ('00000000-0000-7199-8000-000000000001', 10001, 0,
     'WO-TEST-001', 'NEW', 'HIGH',
     'ffffffff-0000-0000-0000-000000000001', NULL,
     'Boiler fault — ignition failure on cold start.', NOW() - INTERVAL '2 hours', 1),
    ('00000000-0000-7199-8000-000000000001', 10002, 1,
     'WO-TEST-001', 'ASSIGNED', 'HIGH',
     'ffffffff-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000001',
     'Boiler fault — ignition failure on cold start.', NOW() - INTERVAL '2 hours', 2),
    ('00000000-0000-7199-8000-000000000001', 10003, 1,
     'WO-TEST-001', 'IN_PROGRESS', 'HIGH',
     'ffffffff-0000-0000-0000-000000000001', 'cccccccc-0000-0000-0000-000000000001',
     'Boiler fault — ignition failure on cold start.', NOW() - INTERVAL '2 hours', 3)
ON CONFLICT (id, REV) DO NOTHING;
