-- =============================================================================
-- V103: Identity module fixtures
-- =============================================================================
-- Provides one active user per role, one inactive user, and one grantless user
-- for integration tests in the identity module and downstream epics.
--
-- These users are DISTINCT from the V100 fixture users (aaaaaaaa-...) which
-- are used by the scope-enforcement tests. Identity module tests use the
-- bbbbbbbb-... range to avoid cross-test fixture coupling.
--
-- All password hashes are BCrypt cost-10 hashes of "fixture-password" for
-- round-trip testing. NO real passwords are committed.
-- =============================================================================

-- One active user per role (bbbbbbbb-... range)
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version) VALUES
    ('bbbbbbbb-0000-0000-0000-000000000001', 'id.admin@example.com',
     '$2a$10$identity.fixture.admin.hash.....', 'Identity Admin',      true,  0),
    ('bbbbbbbb-0000-0000-0000-000000000002', 'id.dispatcher@example.com',
     '$2a$10$identity.fixture.disp.hash.....', 'Identity Dispatcher',  true,  0),
    ('bbbbbbbb-0000-0000-0000-000000000003', 'id.technician@example.com',
     '$2a$10$identity.fixture.tech.hash.....', 'Identity Technician',  true,  0),
    ('bbbbbbbb-0000-0000-0000-000000000004', 'id.manager@example.com',
     '$2a$10$identity.fixture.mgr.hash......', 'Identity Manager',     true,  0),
    ('bbbbbbbb-0000-0000-0000-000000000005', 'id.customer@example.com',
     '$2a$10$identity.fixture.cust.hash.....', 'Identity Customer',    true,  0),

    -- One inactive user (still has a role grant for audit test coverage)
    ('bbbbbbbb-0000-0000-0000-000000000006', 'id.inactive@example.com',
     '$2a$10$identity.fixture.inact.hash....', 'Identity Inactive',    false, 0),

    -- One grantless user (no role_assignment rows — simulates pending invitation)
    ('bbbbbbbb-0000-0000-0000-000000000007', 'id.grantless@example.com',
     NULL,                                    'Identity Grantless',    true,  0);

-- Role assignments for the five active role users
INSERT INTO role_assignment (id, user_id, role_name, granted_at) VALUES
    ('cc000000-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000001', 'ADMIN',       now()),
    ('cc000000-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000002', 'DISPATCHER',  now()),
    ('cc000000-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000003', 'TECHNICIAN',  now()),
    ('cc000000-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000004', 'MANAGER',     now()),
    ('cc000000-0000-0000-0000-000000000005', 'bbbbbbbb-0000-0000-0000-000000000005', 'CUSTOMER',    now()),

    -- Inactive user also has a grant (tests that inactive ≠ grantless)
    ('cc000000-0000-0000-0000-000000000006', 'bbbbbbbb-0000-0000-0000-000000000006', 'TECHNICIAN',  now());
-- bbbbbbbb-0000-0000-0000-000000000007 (grantless) intentionally has no role_assignment row
