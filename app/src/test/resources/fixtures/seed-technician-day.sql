-- seed-technician-day.sql
-- Idempotent fixture for WO-154: technician today's-jobs API.
-- Reference date for this fixture: 2026-09-15 (pass ?date=2026-09-15 in tests)
--
-- UUID prefix convention:
--   00000000-0000-7154-0000-XXXXXXXXXXXX  WO-154 work orders
--
-- Technician IDs match TestJwtFactory constants:
--   TECH_ONE_ID = cccccccc-0000-0000-0000-000000000001
--   TECH_TWO_ID = cccccccc-0000-0000-0000-000000000002
--
-- Base users, customer, sites, technicians are inserted with ON CONFLICT DO NOTHING
-- so this fixture can run standalone or alongside fixtures.sql.

-- ---- App users ---------------------------------------------------------------
INSERT INTO app_user (id, email, password_hash, full_name, active, version) VALUES
    ('dddddddd-0000-0000-0000-000000000001', 'tech1@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Tech One', TRUE, 0),
    ('dddddddd-0000-0000-0000-000000000002', 'tech2@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     'Tech Two', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Customer and sites ------------------------------------------------------
INSERT INTO customer (id, name, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'Acme Corp', 0)
ON CONFLICT (id) DO NOTHING;

-- Site with contact_phone for masking tests
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

-- ---- Work orders for Tech One on 2026-09-15 ----------------------------------
-- WO-154-001: Scheduled today (morning) — ASSIGNED, HIGH
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7154-0000-000000000001',
    'WO154-001', 'ASSIGNED', 'HIGH',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T08:00:00Z', '2026-09-15T10:00:00Z',
    '2026-09-15T12:00:00Z', FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- WO-154-002: Scheduled today (afternoon) — IN_PROGRESS, MEDIUM
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7154-0000-000000000002',
    'WO154-002', 'IN_PROGRESS', 'MEDIUM',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T13:00:00Z', '2026-09-15T15:00:00Z',
    '2026-09-15T17:00:00Z', FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- WO-154-003: Carry-over — yesterday's IN_PROGRESS, CRITICAL (must still appear)
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7154-0000-000000000003',
    'WO154-003', 'IN_PROGRESS', 'CRITICAL',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-14T08:00:00Z', '2026-09-14T10:00:00Z',
    '2026-09-15T08:00:00Z', TRUE, 0
) ON CONFLICT (id) DO NOTHING;

-- WO-154-004: At-risk — scheduled today, at_risk=TRUE
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7154-0000-000000000004',
    'WO154-004', 'ASSIGNED', 'HIGH',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-15T09:00:00Z', '2026-09-15T11:00:00Z',
    '2026-09-15T10:00:00Z', TRUE, 5
) ON CONFLICT (id) DO NOTHING;

-- WO-154-005: Null-window carry-over — ON_HOLD with no scheduled window
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7154-0000-000000000005',
    'WO154-005', 'ON_HOLD', 'MEDIUM',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    NULL, NULL,
    '2026-09-15T20:00:00Z', FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- WO-154-006: COMPLETED yesterday — must NOT appear (state not in carry-over set)
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     at_risk, version)
VALUES (
    '00000000-0000-7154-0000-000000000006',
    'WO154-006', 'COMPLETED', 'LOW',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-14T06:00:00Z', '2026-09-14T08:00:00Z',
    FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- WO-154-007: TOMORROW — must NOT appear for date=2026-09-15
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     at_risk, version)
VALUES (
    '00000000-0000-7154-0000-000000000007',
    'WO154-007', 'ASSIGNED', 'MEDIUM',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    '2026-09-16T08:00:00Z', '2026-09-16T10:00:00Z',
    FALSE, 0
) ON CONFLICT (id) DO NOTHING;

-- WO-154-008: Tech Two's job — must NOT appear for Tech One
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     scheduled_window_start, scheduled_window_end,
     at_risk, version)
VALUES (
    '00000000-0000-7154-0000-000000000008',
    'WO154-008', 'ASSIGNED', 'HIGH',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000002',
    '2026-09-15T09:00:00Z', '2026-09-15T11:00:00Z',
    FALSE, 0
) ON CONFLICT (id) DO NOTHING;
