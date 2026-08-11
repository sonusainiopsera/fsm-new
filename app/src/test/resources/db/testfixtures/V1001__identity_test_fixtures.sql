-- =============================================================================
-- V1001: Identity test fixtures
-- One user per AppRole (ADMIN, DISPATCHER, TECHNICIAN, MANAGER, CUSTOMER),
-- one inactive user, and one user with no role_assignment.
--
-- UUIDs follow the ffffffff-… fixed-pattern convention for recognisable test IDs.
-- All BCrypt hashes are test-only placeholders — the hash for 'password' at cost 10.
-- =============================================================================

-- ── Users ─────────────────────────────────────────────────────────────────────

INSERT INTO app_user (id, email, password_hash, full_name, is_active) VALUES
    ('ffffffff-0001-0001-0001-000000000001', 'admin@identity.test',      '$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', 'Identity Admin',       TRUE),
    ('ffffffff-0002-0002-0002-000000000002', 'dispatcher@identity.test', '$2a$10$BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB', 'Identity Dispatcher',  TRUE),
    ('ffffffff-0003-0003-0003-000000000003', 'tech@identity.test',       '$2a$10$CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC', 'Identity Technician',  TRUE),
    ('ffffffff-0004-0004-0004-000000000004', 'manager@identity.test',    '$2a$10$DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD', 'Identity Manager',     TRUE),
    ('ffffffff-0005-0005-0005-000000000005', 'customer@identity.test',   '$2a$10$EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE', 'Identity Customer',    TRUE),
    ('ffffffff-0006-0006-0006-000000000006', 'inactive@identity.test',   '$2a$10$FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF', 'Identity Inactive',    FALSE),
    ('ffffffff-0007-0007-0007-000000000007', 'norole@identity.test',     '$2a$10$GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG', 'Identity No-Role',     TRUE)
ON CONFLICT DO NOTHING;

-- ── Role assignments (one per ratified role; inactive + no-role users skipped) ─

INSERT INTO role_assignment (id, user_id, role_name, granted_at, granted_by) VALUES
    ('ffffffff-a001-a001-a001-000000000001', 'ffffffff-0001-0001-0001-000000000001', 'ADMIN',       NOW(), NULL),
    ('ffffffff-a002-a002-a002-000000000002', 'ffffffff-0002-0002-0002-000000000002', 'DISPATCHER',  NOW(), 'ffffffff-0001-0001-0001-000000000001'),
    ('ffffffff-a003-a003-a003-000000000003', 'ffffffff-0003-0003-0003-000000000003', 'TECHNICIAN',  NOW(), 'ffffffff-0001-0001-0001-000000000001'),
    ('ffffffff-a004-a004-a004-000000000004', 'ffffffff-0004-0004-0004-000000000004', 'MANAGER',     NOW(), 'ffffffff-0001-0001-0001-000000000001'),
    ('ffffffff-a005-a005-a005-000000000005', 'ffffffff-0005-0005-0005-000000000005', 'CUSTOMER',    NOW(), 'ffffffff-0001-0001-0001-000000000001')
ON CONFLICT DO NOTHING;
