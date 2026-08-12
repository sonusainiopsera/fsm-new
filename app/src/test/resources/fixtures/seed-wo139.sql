-- seed-wo139.sql
-- Fixtures for ReassignmentControllerIT (WO-139).
-- UUID prefix: 00000000-0000-7139-8000-XXXXXXXXXXXX
--
-- Creates:
--   WO_ASSIGNED_ID   (ASSIGNED)    → eligible for reassignment
--   WO_EN_ROUTE_ID   (EN_ROUTE)    → eligible for reassignment
--   WO_IN_PROGRESS_ID(IN_PROGRESS) → eligible for reassignment
--   WO_ON_HOLD_ID    (ON_HOLD)     → eligible for reassignment
--   WO_NEW_ID        (NEW)         → illegal state for reassignment (409)
--   WO_COMPLETED_ID  (COMPLETED)   → illegal state for reassignment (409)
--   WO_CANCELLED_ID  (CANCELLED)   → illegal state for reassignment (409)
--
-- TECH_CURRENT  (7139-0020) = technician currently holding the active assignment
-- TECH_ELIGIBLE (7139-0010) = technician eligible for reassignment (stub returns eligible)
-- TECH_INELIGIBLE(7139-0011) = technician ineligible (stub returns CERTIFICATION_MISSING)
--
-- Depends on seed-core.sql for site 00000000-0000-7013-8000-000000000001.

-- ── Work orders ───────────────────────────────────────────────────────────────

INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id,
                        fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7139-8000-000000000001',
    'WO139-ASSIGNED', 'ASSIGNED', 'HIGH',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    'ELECTRICAL', now(), ''
) ON CONFLICT (id) DO NOTHING;

INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id,
                        fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7139-8000-000000000002',
    'WO139-EN-ROUTE', 'EN_ROUTE', 'MEDIUM',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    'MECHANICAL', now(), ''
) ON CONFLICT (id) DO NOTHING;

INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id,
                        fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7139-8000-000000000003',
    'WO139-IN-PROGRESS', 'IN_PROGRESS', 'HIGH',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    'HVAC', now(), ''
) ON CONFLICT (id) DO NOTHING;

INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id,
                        fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7139-8000-000000000004',
    'WO139-ON-HOLD', 'ON_HOLD', 'MEDIUM',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    'PLUMBING', now(), ''
) ON CONFLICT (id) DO NOTHING;

INSERT INTO work_order (id, reference, state, priority, site_id,
                        fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7139-8000-000000000005',
    'WO139-NEW', 'NEW', 'LOW',
    '00000000-0000-7013-8000-000000000001',
    'MECHANICAL', now(), ''
) ON CONFLICT (id) DO NOTHING;

INSERT INTO work_order (id, reference, state, priority, site_id,
                        fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7139-8000-000000000006',
    'WO139-COMPLETED', 'COMPLETED', 'LOW',
    '00000000-0000-7013-8000-000000000001',
    'ELECTRICAL', now(), ''
) ON CONFLICT (id) DO NOTHING;

INSERT INTO work_order (id, reference, state, priority, site_id,
                        fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7139-8000-000000000007',
    'WO139-CANCELLED', 'CANCELLED', 'LOW',
    '00000000-0000-7013-8000-000000000001',
    'ELECTRICAL', now(), ''
) ON CONFLICT (id) DO NOTHING;

-- ── Assignment rows for the in-flight work orders ─────────────────────────────
-- All have end_at = NULL (active).

INSERT INTO assignment (id, work_order_id, technician_id, assigned_at, created_at,
                        snapshot_stale, version)
VALUES (
    '00000000-0000-7139-8000-100000000001',
    '00000000-0000-7139-8000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    now(), now(), false, 0
) ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment (id, work_order_id, technician_id, assigned_at, created_at,
                        snapshot_stale, version)
VALUES (
    '00000000-0000-7139-8000-100000000002',
    '00000000-0000-7139-8000-000000000002',
    '00000000-0000-7139-8000-000000000020',
    now(), now(), false, 0
) ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment (id, work_order_id, technician_id, assigned_at, created_at,
                        snapshot_stale, version)
VALUES (
    '00000000-0000-7139-8000-100000000003',
    '00000000-0000-7139-8000-000000000003',
    '00000000-0000-7139-8000-000000000020',
    now(), now(), false, 0
) ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment (id, work_order_id, technician_id, assigned_at, created_at,
                        snapshot_stale, version)
VALUES (
    '00000000-0000-7139-8000-100000000004',
    '00000000-0000-7139-8000-000000000004',
    '00000000-0000-7139-8000-000000000020',
    now(), now(), false, 0
) ON CONFLICT (id) DO NOTHING;
