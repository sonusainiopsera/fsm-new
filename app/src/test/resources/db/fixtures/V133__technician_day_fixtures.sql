-- =============================================================================
-- V133: Technician day-list test fixtures (WO-154)
--
-- Uses existing technicians from V100 (TECH_1, TECH_2) and sites (Site A1, A2, B1).
-- Test day: 2026-09-15 UTC.
--
-- Fixture topology:
--   wo-DAY-0001  TECH_1  scheduled today (2026-09-15 08:00Z)            ASSIGNED  HIGH   with asset, at-risk SLA
--   wo-DAY-0002  TECH_1  scheduled today (2026-09-15 10:00Z)            EN_ROUTE  MEDIUM no asset
--   wo-DAY-0003  TECH_1  carry-over: ASSIGNED, window 2026-09-14        ASSIGNED  LOW    (earlier day)
--   wo-DAY-0004  TECH_1  carry-over: IN_PROGRESS, null window           IN_PROGRESS CRITICAL
--   wo-DAY-0005  TECH_2  scheduled today (scope isolation check)        ASSIGNED  HIGH
--   wo-DAY-0006  TECH_1  COMPLETED from today (should NOT appear)       COMPLETED HIGH
-- =============================================================================

INSERT INTO customer (id, name, contact_phone, is_active, version)
VALUES ('cd000000-0000-0000-0000-000000000001', 'Day Test Customer', '+44 20 1234 5678', true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO site (id, customer_id, name, address, latitude, longitude, version)
VALUES ('cd100000-0000-0000-0000-000000000001',
        'cd000000-0000-0000-0000-000000000001',
        'Day Test Site Alpha', '10 Alpha Lane, London', 51.500000, -0.120000, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO asset (id, site_id, name, asset_type, asset_tag, version)
VALUES ('cd200000-0000-0000-0000-000000000001',
        'cd100000-0000-0000-0000-000000000001',
        'HVAC Unit Alpha', 'HVAC', 'HVAC-ALPHA-001', 0)
ON CONFLICT (id) DO NOTHING;

-- wo-DAY-0001: today, TECH_1, at-risk (atRiskAt in the past)
INSERT INTO work_order (id, site_id, customer_id, asset_id, assigned_technician_id,
                        state, priority, description, reference,
                        fault_description, resolution_due_at, at_risk_at,
                        scheduled_window_start, scheduled_window_end,
                        created_at, updated_at, version)
VALUES ('cd300000-0000-0000-0000-000000000001',
        'cd100000-0000-0000-0000-000000000001',
        'cd000000-0000-0000-0000-000000000001',
        'cd200000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000011',
        'ASSIGNED', 'HIGH', 'HVAC failure urgent', 'REF-DAY-001',
        'Unit not cooling', '2026-09-15T12:00:00Z', '2026-08-01T09:00:00Z',
        '2026-09-15T08:00:00Z', '2026-09-15T10:00:00Z',
        '2026-09-15T07:00:00Z', '2026-09-15T07:00:00Z', 0)
ON CONFLICT DO NOTHING;

-- wo-DAY-0002: today, TECH_1, no asset
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id,
                        state, priority, description, reference,
                        fault_description, resolution_due_at,
                        scheduled_window_start, scheduled_window_end,
                        created_at, updated_at, version)
VALUES ('cd300000-0000-0000-0000-000000000002',
        'cd100000-0000-0000-0000-000000000001',
        'cd000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000011',
        'EN_ROUTE', 'MEDIUM', 'Inspection visit', 'REF-DAY-002',
        'Routine inspection', '2026-09-15T16:00:00Z',
        '2026-09-15T10:00:00Z', '2026-09-15T12:00:00Z',
        '2026-09-15T07:00:00Z', '2026-09-15T07:00:00Z', 0)
ON CONFLICT DO NOTHING;

-- wo-DAY-0003: carry-over from 2026-09-14, TECH_1
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id,
                        state, priority, description, reference,
                        fault_description, resolution_due_at,
                        scheduled_window_start, scheduled_window_end,
                        created_at, updated_at, version)
VALUES ('cd300000-0000-0000-0000-000000000003',
        'cd100000-0000-0000-0000-000000000001',
        'cd000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000011',
        'ASSIGNED', 'LOW', 'Carry-over job from yesterday', 'REF-DAY-003',
        'Deferred from previous day', '2026-09-15T17:00:00Z',
        '2026-09-14T08:00:00Z', '2026-09-14T10:00:00Z',
        '2026-09-14T07:00:00Z', '2026-09-15T07:00:00Z', 0)
ON CONFLICT DO NOTHING;

-- wo-DAY-0004: carry-over, null scheduled window, TECH_1
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id,
                        state, priority, description, reference,
                        fault_description, resolution_due_at,
                        created_at, updated_at, version)
VALUES ('cd300000-0000-0000-0000-000000000004',
        'cd100000-0000-0000-0000-000000000001',
        'cd000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000011',
        'IN_PROGRESS', 'CRITICAL', 'No window set carry-over', 'REF-DAY-004',
        'Ongoing critical fault', '2026-09-15T18:00:00Z',
        '2026-09-15T07:00:00Z', '2026-09-15T07:00:00Z', 0)
ON CONFLICT DO NOTHING;

-- wo-DAY-0005: today, TECH_2 (scope isolation — must not appear in TECH_1 response)
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id,
                        state, priority, description, reference,
                        fault_description, resolution_due_at,
                        scheduled_window_start, scheduled_window_end,
                        created_at, updated_at, version)
VALUES ('cd300000-0000-0000-0000-000000000005',
        'cd100000-0000-0000-0000-000000000001',
        'cd000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000012',
        'ASSIGNED', 'HIGH', 'Tech 2 job today', 'REF-DAY-005',
        'Other technician fault', '2026-09-15T14:00:00Z',
        '2026-09-15T09:00:00Z', '2026-09-15T11:00:00Z',
        '2026-09-15T07:00:00Z', '2026-09-15T07:00:00Z', 0)
ON CONFLICT DO NOTHING;

-- wo-DAY-0006: COMPLETED today, TECH_1 (terminal state — must NOT appear)
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id,
                        state, priority, description, reference,
                        fault_description,
                        scheduled_window_start, scheduled_window_end,
                        created_at, updated_at, version)
VALUES ('cd300000-0000-0000-0000-000000000006',
        'cd100000-0000-0000-0000-000000000001',
        'cd000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000011',
        'COMPLETED', 'MEDIUM', 'Completed today', 'REF-DAY-006',
        'Resolved earlier',
        '2026-09-15T06:00:00Z', '2026-09-15T08:00:00Z',
        '2026-09-15T06:00:00Z', '2026-09-15T08:00:00Z', 0)
ON CONFLICT DO NOTHING;
