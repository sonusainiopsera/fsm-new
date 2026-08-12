-- =============================================================================
-- V132: SLA breach test fixtures (WO-144)
-- Five scenarios covering all acceptance criteria:
--   wo-BREACH-0001: response-only breach (response missed, resolution met in time)
--   wo-BREACH-0002: resolution breach (resolution deadline missed)
--   wo-BREACH-0003: pause-saved near-breach (SLA clock paused; effective deadline not yet passed)
--   wo-BREACH-0004: breached-then-closed (breach detected, work order now closed)
--   wo-BREACH-0005: unattributed breach awaiting reason code
-- =============================================================================

-- ── Shared customer ─────────────────────────────────────────────────────────

INSERT INTO app_user (id, email, role, created_at, updated_at, version)
VALUES ('ee100000-0000-0000-0000-000000000001', 'breach-customer@example.test',
        'CUSTOMER', now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO customer (id, name, created_at, updated_at, version)
VALUES ('ff100000-0000-0000-0000-000000000001', 'Breach Test Customer', now(), now(), 0)
ON CONFLICT DO NOTHING;

-- ── Scenario 1: Response-only breach ────────────────────────────────────────
-- WO was ASSIGNED past its response deadline but resolved before resolution deadline.

INSERT INTO work_order (id, customer_id, title, description, state, priority,
                        response_due_at, resolution_due_at,
                        created_at, updated_at, version)
VALUES ('aa100000-0000-0000-0000-000000000001',
        'ff100000-0000-0000-0000-000000000001',
        'Breach Test - Response Only', 'Response deadline missed only',
        'COMPLETED', 'HIGH',
        '2026-08-10T09:00:00Z', '2026-08-10T17:00:00Z',
        '2026-08-10T08:00:00Z', now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO sla_breach (id, work_order_id, breach_type, effective_deadline,
                        detected_at, overrun_minutes, paused_minutes_excluded,
                        final_overrun_minutes, version)
VALUES ('bb100000-0000-0000-0000-000000000001',
        'aa100000-0000-0000-0000-000000000001',
        'RESPONSE', '2026-08-10T09:00:00Z', '2026-08-10T09:15:00Z',
        15, 0, 15, 0)
ON CONFLICT DO NOTHING;

-- ── Scenario 2: Resolution breach ───────────────────────────────────────────

INSERT INTO work_order (id, customer_id, title, description, state, priority,
                        response_due_at, resolution_due_at,
                        created_at, updated_at, version)
VALUES ('aa100000-0000-0000-0000-000000000002',
        'ff100000-0000-0000-0000-000000000001',
        'Breach Test - Resolution', 'Resolution deadline missed',
        'IN_PROGRESS', 'CRITICAL',
        '2026-08-11T09:00:00Z', '2026-08-11T13:00:00Z',
        '2026-08-11T08:00:00Z', now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO sla_breach (id, work_order_id, breach_type, effective_deadline,
                        detected_at, overrun_minutes, paused_minutes_excluded,
                        reason_code, reason_note, attributed_by, attributed_at, version)
VALUES ('bb100000-0000-0000-0000-000000000002',
        'aa100000-0000-0000-0000-000000000002',
        'RESOLUTION', '2026-08-11T13:00:00Z', '2026-08-11T13:01:00Z',
        1, 0,
        'PARTS_UNAVAILABLE', 'Spare part backlog delayed repair',
        NULL, NULL, 0)
ON CONFLICT DO NOTHING;

-- ── Scenario 3: Pause-saved near-breach ─────────────────────────────────────
-- Raw elapsed time exceeds original deadline, but effective deadline (with pause) has not passed.
-- No breach record exists — this is intentional.

INSERT INTO work_order (id, customer_id, title, description, state, priority,
                        response_due_at, resolution_due_at,
                        created_at, updated_at, version)
VALUES ('aa100000-0000-0000-0000-000000000003',
        'ff100000-0000-0000-0000-000000000001',
        'Breach Test - Pause Saved', 'Clock paused; effective deadline not yet passed',
        'IN_PROGRESS', 'MEDIUM',
        '2026-08-12T09:00:00Z', '2026-08-12T13:00:00Z',
        '2026-08-12T08:00:00Z', now(), 0)
ON CONFLICT DO NOTHING;

-- SLA clock was paused for 120 minutes — effective resolution deadline is 15:00, not yet breached.
INSERT INTO sla_clock_pause (id, work_order_id, hold_reason_code, paused_at, resumed_at, version)
VALUES ('cc100000-0000-0000-0000-000000000001',
        'aa100000-0000-0000-0000-000000000003',
        'AWAITING_PARTS', '2026-08-12T10:00:00Z', '2026-08-12T12:00:00Z', 0)
ON CONFLICT DO NOTHING;

-- ── Scenario 4: Breached-then-closed ────────────────────────────────────────

INSERT INTO work_order (id, customer_id, title, description, state, priority,
                        response_due_at, resolution_due_at,
                        created_at, updated_at, version)
VALUES ('aa100000-0000-0000-0000-000000000004',
        'ff100000-0000-0000-0000-000000000001',
        'Breach Test - Closed After Breach', 'Work order closed after breach was recorded',
        'CLOSED', 'HIGH',
        '2026-08-13T09:00:00Z', '2026-08-13T13:00:00Z',
        '2026-08-13T08:00:00Z', now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO sla_breach (id, work_order_id, breach_type, effective_deadline,
                        detected_at, overrun_minutes, paused_minutes_excluded,
                        final_overrun_minutes, reason_code, reason_note,
                        attributed_by, attributed_at, version)
VALUES ('bb100000-0000-0000-0000-000000000004',
        'aa100000-0000-0000-0000-000000000004',
        'RESOLUTION', '2026-08-13T13:00:00Z', '2026-08-13T13:10:00Z',
        10, 0, 45,
        'TRAVEL_DISRUPTION', 'Train cancellation delayed technician',
        NULL, NULL, 0)
ON CONFLICT DO NOTHING;

-- ── Scenario 5: Unattributed breach awaiting reason code ────────────────────

INSERT INTO work_order (id, customer_id, title, description, state, priority,
                        response_due_at, resolution_due_at,
                        created_at, updated_at, version)
VALUES ('aa100000-0000-0000-0000-000000000005',
        'ff100000-0000-0000-0000-000000000001',
        'Breach Test - Unattributed', 'Breach detected, reason code not yet assigned',
        'IN_PROGRESS', 'MEDIUM',
        '2026-08-14T09:00:00Z', '2026-08-14T13:00:00Z',
        '2026-08-14T08:00:00Z', now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO sla_breach (id, work_order_id, breach_type, effective_deadline,
                        detected_at, overrun_minutes, paused_minutes_excluded,
                        version)
VALUES ('bb100000-0000-0000-0000-000000000005',
        'aa100000-0000-0000-0000-000000000005',
        'RESOLUTION', '2026-08-14T13:00:00Z', '2026-08-14T13:05:00Z',
        5, 0, 0)
ON CONFLICT DO NOTHING;
