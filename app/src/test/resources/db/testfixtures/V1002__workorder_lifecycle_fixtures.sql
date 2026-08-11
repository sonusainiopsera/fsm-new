-- =============================================================================
-- V1002: Work order lifecycle test fixtures
-- One work order in each of the eight lifecycle states.
-- Reusable by downstream stories — reference by the fixed UUIDs below.
--
-- UUID prefix ee000000-…  = lifecycle-state fixture work orders
-- All work orders use the Acme HQ site (aaaaaaaa-0001-…) from V4 seed data.
-- =============================================================================

-- NEW
INSERT INTO work_order (id, title, state, site_id, priority, created_at, version)
VALUES ('ee000000-0001-0001-0001-000000000001', 'Lifecycle Fixture: NEW',
        'NEW', 'aaaaaaaa-0001-0001-0001-000000000001', 'MEDIUM', now(), 0)
ON CONFLICT (id) DO NOTHING;

-- ASSIGNED
INSERT INTO work_order (id, title, state, site_id, assigned_technician_id, priority, created_at, version)
VALUES ('ee000000-0002-0002-0002-000000000002', 'Lifecycle Fixture: ASSIGNED',
        'ASSIGNED', 'aaaaaaaa-0001-0001-0001-000000000001', 'tech-lc-001', 'MEDIUM', now(), 0)
ON CONFLICT (id) DO NOTHING;

-- EN_ROUTE
INSERT INTO work_order (id, title, state, site_id, assigned_technician_id, priority, created_at, version)
VALUES ('ee000000-0003-0003-0003-000000000003', 'Lifecycle Fixture: EN_ROUTE',
        'EN_ROUTE', 'aaaaaaaa-0001-0001-0001-000000000001', 'tech-lc-001', 'MEDIUM', now(), 0)
ON CONFLICT (id) DO NOTHING;

-- IN_PROGRESS
INSERT INTO work_order (id, title, state, site_id, assigned_technician_id, priority, created_at, version)
VALUES ('ee000000-0004-0004-0004-000000000004', 'Lifecycle Fixture: IN_PROGRESS',
        'IN_PROGRESS', 'aaaaaaaa-0001-0001-0001-000000000001', 'tech-lc-001', 'MEDIUM', now(), 0)
ON CONFLICT (id) DO NOTHING;

-- ON_HOLD
INSERT INTO work_order (id, title, state, site_id, assigned_technician_id, priority, created_at, version)
VALUES ('ee000000-0005-0005-0005-000000000005', 'Lifecycle Fixture: ON_HOLD',
        'ON_HOLD', 'aaaaaaaa-0001-0001-0001-000000000001', 'tech-lc-001', 'MEDIUM', now(), 0)
ON CONFLICT (id) DO NOTHING;

-- COMPLETED
INSERT INTO work_order (id, title, state, site_id, assigned_technician_id, priority, created_at, version)
VALUES ('ee000000-0006-0006-0006-000000000006', 'Lifecycle Fixture: COMPLETED',
        'COMPLETED', 'aaaaaaaa-0001-0001-0001-000000000001', 'tech-lc-001', 'MEDIUM', now(), 0)
ON CONFLICT (id) DO NOTHING;

-- CLOSED (terminal)
INSERT INTO work_order (id, title, state, site_id, assigned_technician_id, priority, created_at, version)
VALUES ('ee000000-0007-0007-0007-000000000007', 'Lifecycle Fixture: CLOSED',
        'CLOSED', 'aaaaaaaa-0001-0001-0001-000000000001', 'tech-lc-001', 'MEDIUM', now(), 0)
ON CONFLICT (id) DO NOTHING;

-- CANCELLED (terminal)
INSERT INTO work_order (id, title, state, site_id, priority, created_at, version)
VALUES ('ee000000-0008-0008-0008-000000000008', 'Lifecycle Fixture: CANCELLED',
        'CANCELLED', 'aaaaaaaa-0001-0001-0001-000000000001', 'MEDIUM', now(), 0)
ON CONFLICT (id) DO NOTHING;
