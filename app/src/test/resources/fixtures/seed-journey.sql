-- seed-journey.sql
-- Idempotent fixture for WO-160: end-to-end technician field execution regression suite.
-- Reference date: 2026-09-15 (pass ?date=2026-09-15 in tests).
--
-- UUID prefix convention:
--   00000000-0000-7160-0000-XXXXXXXXXXXX  WO-160 work orders
--
-- Technician IDs match TestJwtFactory constants:
--   TECH_ONE_ID = cccccccc-0000-0000-0000-000000000001
--   TECH_TWO_ID = cccccccc-0000-0000-0000-000000000002
--
-- Base users, customers, sites and technicians are inserted with ON CONFLICT DO NOTHING
-- so this fixture can coexist with seed-technician-day.sql.

-- ---- App users ---------------------------------------------------------------
INSERT INTO app_user (id, email, password_hash, full_name, active, version) VALUES
    ('dddddddd-0000-0000-0000-000000000001', 'tech1@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Tech One', TRUE, 0),
    ('dddddddd-0000-0000-0000-000000000002', 'tech2@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Tech Two', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Customer and site -------------------------------------------------------
INSERT INTO customer (id, name, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'Acme Corp', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO site (id, name, customer_id, address_line1, city, postcode, latitude, longitude,
                  contact_phone, version)
VALUES
    ('bbbbbbbb-0000-0000-0000-000000000001', 'Acme HQ',
     'aaaaaaaa-0000-0000-0000-000000000001',
     '1 Industrial Way', 'Manchester', 'M1 1AA',
     53.483959, -2.244644,
     '+44 161 867 5309', 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Technicians -------------------------------------------------------------
INSERT INTO technician (id, user_id, full_name, version) VALUES
    ('cccccccc-0000-0000-0000-000000000001', 'dddddddd-0000-0000-0000-000000000001', 'Tech One', 0),
    ('cccccccc-0000-0000-0000-000000000002', 'dddddddd-0000-0000-0000-000000000002', 'Tech Two', 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Work orders — every lifecycle state for the journey suite ---------------

-- Journey-001: ASSIGNED — golden path starting point for Tech One
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000001',
    'JRN-001', 'ASSIGNED', 'HIGH',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T08:00:00Z', '2026-09-15T10:00:00Z',
    '2026-09-15T12:00:00Z', FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- Journey-002: EN_ROUTE — already en route for Tech One
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000002',
    'JRN-002', 'EN_ROUTE', 'MEDIUM',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T09:00:00Z', '2026-09-15T11:00:00Z',
    '2026-09-15T13:00:00Z', FALSE, 1
) ON CONFLICT (id) DO NOTHING;

-- Journey-003: IN_PROGRESS — active job for completion tests (has labour entry added below)
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000003',
    'JRN-003', 'IN_PROGRESS', 'HIGH',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T07:00:00Z', '2026-09-15T09:00:00Z',
    '2026-09-15T11:00:00Z', FALSE, 2
) ON CONFLICT (id) DO NOTHING;

-- Journey-004: IN_PROGRESS — at-risk SLA for guard tests (no labour entry — completion blocked)
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000004',
    'JRN-004', 'IN_PROGRESS', 'CRITICAL',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-14T08:00:00Z', '2026-09-14T10:00:00Z',
    '2026-09-15T08:00:00Z', TRUE, 3
) ON CONFLICT (id) DO NOTHING;

-- Journey-005: ON_HOLD — paused job for resume tests
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000005',
    'JRN-005', 'ON_HOLD', 'MEDIUM',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T06:00:00Z', '2026-09-15T08:00:00Z',
    '2026-09-15T10:00:00Z', FALSE, 4
) ON CONFLICT (id) DO NOTHING;

-- Journey-006: COMPLETED — closed yesterday
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000006',
    'JRN-006', 'COMPLETED', 'LOW',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-14T06:00:00Z', '2026-09-14T08:00:00Z',
    '2026-09-14T12:00:00Z', FALSE, 5
) ON CONFLICT (id) DO NOTHING;

-- Journey-007: Tech Two's job — cross-technician isolation test
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000007',
    'JRN-007', 'ASSIGNED', 'MEDIUM',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000002',
    '2026-09-15T09:00:00Z', '2026-09-15T11:00:00Z',
    '2026-09-15T15:00:00Z', FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- Journey-008: ASSIGNED — used exclusively for audit revision assertions
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000008',
    'JRN-008', 'ASSIGNED', 'HIGH',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T14:00:00Z', '2026-09-15T16:00:00Z',
    '2026-09-15T18:00:00Z', FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- Journey-009: ASSIGNED — used exclusively for idempotency replay test
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7160-0000-000000000009',
    'JRN-009', 'ASSIGNED', 'MEDIUM',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T15:00:00Z', '2026-09-15T17:00:00Z',
    '2026-09-15T19:00:00Z', FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- ---- Labour entries for jobs that allow completion ---------------------------

-- Labour entry for JRN-003 (IN_PROGRESS, has labour → COMPLETE should be allowed)
INSERT INTO work_order_labour_entry
    (id, work_order_id, technician_id, minutes, notes, created_at)
VALUES (
    '10000000-0000-7160-0000-000000000001',
    '00000000-0000-7160-0000-000000000003',
    'cccccccc-0000-0000-0000-000000000001',
    90,
    'Inspection and component replacement',
    '2026-09-15T09:30:00Z'
) ON CONFLICT (id) DO NOTHING;
