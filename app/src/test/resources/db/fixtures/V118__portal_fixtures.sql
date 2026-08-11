-- V118__portal_fixtures.sql
-- Portal linkage test fixtures for WO-169 adversarial tests.
--
-- Topology:
--   CUSTOMER_A_USER  (aaaaaaaa-...16) linked to ACCT_A (00...01) — ACTIVE
--   CUSTOMER_B_USER  (aaaaaaaa-...17) linked to ACCT_B (00...02) — ACTIVE
--   CUSTOMER_ORPHAN  (aaaaaaaa-...18) — app_user exists, NO portal_account_user row
--
-- Fixture UUIDs use the aaaaaaaa-...1x range to avoid collision with V100 users.
--
-- ACCT_A and ACCT_B already exist in V100 (customer rows 00...01 and 00...02).
-- Sites site_a1 (10...01) and site_b1 (10...03) already exist in V100.
-- Work orders wo_a1 (30...01) and wo_b1 (30...03) already exist in V100.

-- -------------------------------------------------------------------------
-- app_user rows for portal test principals
-- -------------------------------------------------------------------------
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version)
VALUES
    ('aaaaaaaa-0000-0000-0000-000000000016', 'portalA@example.com',   '$2a$10$placeholder.hash.portalA.......', 'Portal User A', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000017', 'portalB@example.com',   '$2a$10$placeholder.hash.portalB.......', 'Portal User B', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000018', 'orphan@example.com',    '$2a$10$placeholder.hash.orphan........', 'Orphan Portal', true, 0)
ON CONFLICT (id) DO NOTHING;

-- -------------------------------------------------------------------------
-- portal_account_user — two active linkages, one orphan (no row)
-- -------------------------------------------------------------------------
INSERT INTO portal_account_user (id, user_id, account_id, status, activated_at, version)
VALUES
    -- CUSTOMER_A: linked to ACCT_A, ACTIVE
    ('f0000000-0000-0000-0000-000000000001',
     'aaaaaaaa-0000-0000-0000-000000000016',
     '00000000-0000-0000-0000-000000000001',
     'ACTIVE',
     now() - INTERVAL '1 day',
     0),
    -- CUSTOMER_B: linked to ACCT_B, ACTIVE
    ('f0000000-0000-0000-0000-000000000002',
     'aaaaaaaa-0000-0000-0000-000000000017',
     '00000000-0000-0000-0000-000000000002',
     'ACTIVE',
     now() - INTERVAL '1 day',
     0)
ON CONFLICT (id) DO NOTHING;

-- CUSTOMER_ORPHAN (aaaaaaaa-...18) intentionally has no portal_account_user row.
-- Tests that probe with this user must receive 404 (scope unavailable).
