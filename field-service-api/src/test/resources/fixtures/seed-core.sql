-- Anonymized seed data for integration and controller tests.
--
-- Five users, one per role, all with deterministic UUIDs:
--
--   ADMIN       a0000000-0000-0000-0000-000000000001
--   MANAGER     a0000000-0000-0000-0000-000000000002
--   DISPATCHER  a0000000-0000-0000-0000-000000000003
--   TECHNICIAN  a0000000-0000-0000-0000-000000000004
--   CUSTOMER    a0000000-0000-0000-0000-000000000005
--
-- Notification preferences:
--   - TECHNICIAN has explicit preferences for WORK_ORDER_ASSIGNED (EMAIL off, IN_APP on, others absent = default-on)
--   - CUSTOMER   has all channels explicitly disabled for SLA_BREACH
--   - ADMIN / MANAGER / DISPATCHER have no preferences (full defaults)

-- ── Users ────────────────────────────────────────────────────────────────────

INSERT INTO app_user (id, email, full_name, role) VALUES
    ('a0000000-0000-0000-0000-000000000001', 'admin@example.test',      'Alice Admin',      'ADMIN'),
    ('a0000000-0000-0000-0000-000000000002', 'manager@example.test',    'Bob Manager',      'MANAGER'),
    ('a0000000-0000-0000-0000-000000000003', 'dispatcher@example.test', 'Carol Dispatcher', 'DISPATCHER'),
    ('a0000000-0000-0000-0000-000000000004', 'tech@example.test',       'Dave Technician',  'TECHNICIAN'),
    ('a0000000-0000-0000-0000-000000000005', 'customer@example.test',   'Eve Customer',     'CUSTOMER')
ON CONFLICT (id) DO NOTHING;

-- ── Notification preferences ─────────────────────────────────────────────────

-- TECHNICIAN: WORK_ORDER_ASSIGNED — EMAIL disabled, IN_APP enabled
--   (SMS and PUSH are absent so they default to enabled=true via resolveEffective)
INSERT INTO notification_preference (id, user_id, category, channel, enabled, version) VALUES
    ('b0000000-0000-0000-0000-000000000001',
     'a0000000-0000-0000-0000-000000000004',
     'WORK_ORDER_ASSIGNED', 'EMAIL',  FALSE, 0),
    ('b0000000-0000-0000-0000-000000000002',
     'a0000000-0000-0000-0000-000000000004',
     'WORK_ORDER_ASSIGNED', 'IN_APP', TRUE,  0)
ON CONFLICT (user_id, category, channel) DO NOTHING;

-- CUSTOMER: SLA_BREACH — all four channels explicitly disabled
INSERT INTO notification_preference (id, user_id, category, channel, enabled, version) VALUES
    ('b0000000-0000-0000-0000-000000000010',
     'a0000000-0000-0000-0000-000000000005',
     'SLA_BREACH', 'EMAIL',  FALSE, 0),
    ('b0000000-0000-0000-0000-000000000011',
     'a0000000-0000-0000-0000-000000000005',
     'SLA_BREACH', 'SMS',    FALSE, 0),
    ('b0000000-0000-0000-0000-000000000012',
     'a0000000-0000-0000-0000-000000000005',
     'SLA_BREACH', 'IN_APP', FALSE, 0),
    ('b0000000-0000-0000-0000-000000000013',
     'a0000000-0000-0000-0000-000000000005',
     'SLA_BREACH', 'PUSH',   FALSE, 0)
ON CONFLICT (user_id, category, channel) DO NOTHING;

-- ── Release validation suite fixtures ────────────────────────────────────────
--
-- These rows supply reference data required by the post-deploy invariant gates.
-- All IDs use the reserved c0000000-... range to avoid clashing with real data.
--
-- Validation account: validator@example.test (DISPATCHER role — non-admin)
--   Used by InvariantGateRunner as the non-privileged probe account.
INSERT INTO app_user (id, email, full_name, role) VALUES
    ('c0000000-0000-0000-0000-000000000001',
     'validator@example.test', 'Validation Runner', 'DISPATCHER')
ON CONFLICT (id) DO NOTHING;

-- Technician with an expired certification — used by GuardNeverFailsOpenGate
-- to assert that ADMIN cannot override a hard certification guard (returns 422).
-- The certification expiry is enforced by the work-order assignment guard;
-- this row is the fixture the gate relies on.
INSERT INTO app_user (id, email, full_name, role) VALUES
    ('c0000000-0000-0000-0000-000000000002',
     'expired-cert-tech@example.test', 'Expired Cert Technician', 'TECHNICIAN')
ON CONFLICT (id) DO NOTHING;
