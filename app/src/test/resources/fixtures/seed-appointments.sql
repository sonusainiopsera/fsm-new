-- seed-appointments.sql
-- Fixtures for appointment protection tests (WO-139).
-- UUID prefix: 00000000-0000-7139-9000-XXXXXXXXXXXX
--
-- Creates:
--   WO_APPT_CONFIRMED  — ASSIGNED, confirmed appointment window in the future
--   WO_APPT_UNCONFIRMED — ASSIGNED, appointment window not confirmed (guard must NOT fire)
--   WO_APPT_PAST       — ASSIGNED, confirmed appointment window in the past (guard must NOT fire)
--
-- Depends on seed-core.sql for site and seed-wo139.sql for TECH_CURRENT.

-- ── Work order with a confirmed future appointment ────────────────────────────
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id,
                        fault_category, created_at, fault_signature_tokens,
                        appointment_window_start, appointment_window_end, appointment_confirmed)
VALUES (
    '00000000-0000-7139-9000-000000000001',
    'WO139-APPT-CONFIRMED', 'ASSIGNED', 'HIGH',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    'HVAC', now(), '',
    now() + INTERVAL '2 hours',
    now() + INTERVAL '4 hours',
    TRUE
) ON CONFLICT (id) DO NOTHING;

-- ── Work order with an unconfirmed appointment (guard must not fire) ──────────
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id,
                        fault_category, created_at, fault_signature_tokens,
                        appointment_window_start, appointment_window_end, appointment_confirmed)
VALUES (
    '00000000-0000-7139-9000-000000000002',
    'WO139-APPT-UNCONFIRMED', 'ASSIGNED', 'MEDIUM',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    'ELECTRICAL', now(), '',
    now() + INTERVAL '2 hours',
    now() + INTERVAL '4 hours',
    FALSE
) ON CONFLICT (id) DO NOTHING;

-- ── Work order with a past confirmed appointment (guard must not fire) ─────────
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id,
                        fault_category, created_at, fault_signature_tokens,
                        appointment_window_start, appointment_window_end, appointment_confirmed)
VALUES (
    '00000000-0000-7139-9000-000000000003',
    'WO139-APPT-PAST', 'ASSIGNED', 'LOW',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    'PLUMBING', now(), '',
    now() - INTERVAL '4 hours',
    now() - INTERVAL '2 hours',
    TRUE
) ON CONFLICT (id) DO NOTHING;

-- ── Assignment rows for appointment test work orders ──────────────────────────
INSERT INTO assignment (id, work_order_id, technician_id, assigned_at, created_at,
                        snapshot_stale, version)
VALUES (
    '00000000-0000-7139-9000-100000000001',
    '00000000-0000-7139-9000-000000000001',
    '00000000-0000-7139-8000-000000000020',
    now(), now(), false, 0
) ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment (id, work_order_id, technician_id, assigned_at, created_at,
                        snapshot_stale, version)
VALUES (
    '00000000-0000-7139-9000-100000000002',
    '00000000-0000-7139-9000-000000000002',
    '00000000-0000-7139-8000-000000000020',
    now(), now(), false, 0
) ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment (id, work_order_id, technician_id, assigned_at, created_at,
                        snapshot_stale, version)
VALUES (
    '00000000-0000-7139-9000-100000000003',
    '00000000-0000-7139-9000-000000000003',
    '00000000-0000-7139-8000-000000000020',
    now(), now(), false, 0
) ON CONFLICT (id) DO NOTHING;
