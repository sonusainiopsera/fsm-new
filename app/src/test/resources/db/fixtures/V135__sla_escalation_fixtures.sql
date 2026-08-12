-- =============================================================================
-- V135: SLA escalation notification test fixtures (WO-146)
-- Provides dispatcher and manager users with anonymized contacts for
-- integration tests that verify SLA escalation fan-out, dedup, quiet-hours,
-- and grace-period escalation scenarios.
-- =============================================================================

-- ── Dispatcher user ──────────────────────────────────────────────────────────

INSERT INTO app_user (id, email, role, created_at, updated_at, version)
VALUES ('ea110000-0000-0000-0000-000000000001',
        'esc-dispatcher@example.test',
        'DISPATCHER', now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO role_assignment (id, user_id, role_name, granted_at)
VALUES ('ea120000-0000-0000-0000-000000000001',
        'ea110000-0000-0000-0000-000000000001',
        'DISPATCHER', now())
ON CONFLICT DO NOTHING;

-- ── Manager user ─────────────────────────────────────────────────────────────

INSERT INTO app_user (id, email, role, created_at, updated_at, version)
VALUES ('ea110000-0000-0000-0000-000000000002',
        'esc-manager@example.test',
        'MANAGER', now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO role_assignment (id, user_id, role_name, granted_at)
VALUES ('ea120000-0000-0000-0000-000000000002',
        'ea110000-0000-0000-0000-000000000002',
        'MANAGER', now())
ON CONFLICT DO NOTHING;

-- ── Shared customer ──────────────────────────────────────────────────────────

INSERT INTO app_user (id, email, role, created_at, updated_at, version)
VALUES ('ea110000-0000-0000-0000-000000000010',
        'esc-customer@example.test',
        'CUSTOMER', now(), now(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO customer (id, name, created_at, updated_at, version)
VALUES ('ea200000-0000-0000-0000-000000000001',
        'Escalation Test Customer', now(), now(), 0)
ON CONFLICT DO NOTHING;

-- ── Work order for escalation tests ─────────────────────────────────────────

INSERT INTO work_order (id, customer_id, title, description, state, priority,
                        response_due_at, resolution_due_at,
                        created_at, updated_at, version)
VALUES ('ea300000-0000-0000-0000-000000000001',
        'ea200000-0000-0000-0000-000000000001',
        'Escalation Test WO', 'SLA risk for escalation tests',
        'ASSIGNED', 'HIGH',
        '2026-08-15T12:00:00Z', '2026-08-15T20:00:00Z',
        now(), now(), 0)
ON CONFLICT DO NOTHING;

-- ── SLA policy rows (mirror V54 seed for test profile) ───────────────────────
-- These rows are inserted directly to ensure the test DB has active policies.
-- The V54 migration already runs for test; these rows use ON CONFLICT DO NOTHING
-- to avoid duplicate-key errors if V54 seeds the same IDs.

INSERT INTO sla_escalation_policy
    (id, event_type, priority, recipient_roles, channels,
     manager_grace_minutes, quiet_hours_start, quiet_hours_end, quiet_hours_zone,
     active, effective_from, effective_to)
VALUES
    ('ea400000-0000-0000-0000-000000000001',
     'SlaRiskFlagged', 'HIGH',
     ARRAY['DISPATCHER', 'MANAGER'], ARRAY['EMAIL'],
     30, 22, 6, 'UTC',
     true, now(), NULL),
    ('ea400000-0000-0000-0000-000000000002',
     'SlaBreached', 'HIGH',
     ARRAY['DISPATCHER', 'MANAGER'], ARRAY['EMAIL'],
     0, NULL, NULL, 'UTC',
     true, now(), NULL)
ON CONFLICT DO NOTHING;
