-- identity-seed.sql
-- Idempotent identity fixture for test and local profiles only.
-- Creates one active user per ratified role, one inactive user, and one user with no role grants.
-- No real personal data: all addresses are example.local, passwords are the BCrypt of 'password'.
--
-- UUIDv7 constants use the 0x7000/0x8000 pattern for test readability.
-- Loaded by IdentitySchemaIT; not applied automatically to all test contexts.

-- ---- Users -------------------------------------------------------------------
-- password_hash values are BCrypt cost-12 hashes of 'TestPassword123!'
-- Pre-computed so the test-suite does not re-encode at cost 12 on every run.
-- Hash: $2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC
-- appearance_preference: four seed users cover LIGHT, DARK, SYSTEM, and null
-- so both API and web suites can run without external dependencies (AC-14).
INSERT INTO app_user (id, email, display_name, password_hash, active, created_at, version, appearance_preference) VALUES
    ('11111111-1111-7000-8000-000000000001', 'admin@example.local',       'Admin User',         '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC', TRUE,  NOW(), 0, NULL),
    ('11111111-1111-7000-8000-000000000002', 'dispatcher@example.local',  'Dispatcher User',    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC', TRUE,  NOW(), 0, 'LIGHT'),
    ('11111111-1111-7000-8000-000000000003', 'technician@example.local',  'Technician User',    '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC', TRUE,  NOW(), 0, 'DARK'),
    ('11111111-1111-7000-8000-000000000004', 'manager@example.local',     'Manager User',       '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC', TRUE,  NOW(), 0, 'SYSTEM'),
    ('11111111-1111-7000-8000-000000000005', 'customer@example.local',    'Customer User',      '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC', TRUE,  NOW(), 0, NULL),
    ('11111111-1111-7000-8000-000000000006', 'inactive@example.local',    'Inactive User',      '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC', FALSE, NOW(), 0, NULL),
    ('11111111-1111-7000-8000-000000000007', 'grantless@example.local',   'Grantless User',     '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC', TRUE,  NOW(), 0, NULL)
ON CONFLICT (id) DO NOTHING;

-- ---- Role grants (five active users get one grant each; inactive user has a grant;
--                   grantless user intentionally has none) ----------------------
INSERT INTO role_assignment (id, user_id, role_name, granted_at) VALUES
    ('22222222-2222-7000-8000-000000000001', '11111111-1111-7000-8000-000000000001', 'ADMIN',      NOW()),
    ('22222222-2222-7000-8000-000000000002', '11111111-1111-7000-8000-000000000002', 'DISPATCHER', NOW()),
    ('22222222-2222-7000-8000-000000000003', '11111111-1111-7000-8000-000000000003', 'TECHNICIAN', NOW()),
    ('22222222-2222-7000-8000-000000000004', '11111111-1111-7000-8000-000000000004', 'MANAGER',    NOW()),
    ('22222222-2222-7000-8000-000000000005', '11111111-1111-7000-8000-000000000005', 'CUSTOMER',   NOW()),
    ('22222222-2222-7000-8000-000000000006', '11111111-1111-7000-8000-000000000006', 'ADMIN',      NOW())
ON CONFLICT (id) DO NOTHING;
-- User 7 (grantless@example.local) intentionally has no role_assignment rows.
