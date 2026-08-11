-- =============================================================================
-- V104: Refresh-rotation fixtures
-- =============================================================================
-- Provides families in all five states needed for offline branch testing:
--   1. VALID         – one unconsumed token, family unexpired and unrevoked
--   2. CONSUMED      – the single token is already consumed (replay scenario)
--   3. REVOKED       – family has a non-null revoked_at (REPLAY_ATTACK reason)
--   4. EXPIRED       – family absolute_expires_at is in the past
--   5. INACTIVE_USER – family belongs to the inactive fixture user (bbbbbbbb-…-000006)
--
-- User references:   bbbbbbbb-0000-0000-0000-000000000001  (id.admin@example.com, active)
--                    bbbbbbbb-0000-0000-0000-000000000006  (id.inactive@example.com, inactive)
-- UUID range:        dd000000-…  families / ee000000-…  tokens
-- Handle material:   none — only SHA-256 hex hashes are stored here.
-- =============================================================================

-- -------------------------------------------------------
-- 1. VALID family — one unconsumed token
-- -------------------------------------------------------
INSERT INTO refresh_token_family (id, user_id, created_at, absolute_expires_at) VALUES
    ('dd000000-0000-0000-0000-000000000001',
     'bbbbbbbb-0000-0000-0000-000000000001',
     now() - INTERVAL '1 hour',
     now() + INTERVAL '7 days');

-- SHA-256 hex of the literal string "valid-fixture-handle-for-unit-tests"
-- echo -n "valid-fixture-handle-for-unit-tests" | sha256sum
-- → f47d59a2f33f4e97b7c9ddcbdef0c8e3c7b6459fa9e0b20d3fe6e7c5a2c8b1d7
-- (placeholder hash — tests that need a real hash compute it at runtime)
INSERT INTO refresh_token (id, family_id, token_hash, issued_at, expires_at) VALUES
    ('ee000000-0000-0000-0000-000000000001',
     'dd000000-0000-0000-0000-000000000001',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
     now() - INTERVAL '1 hour',
     now() + INTERVAL '7 days');

-- -------------------------------------------------------
-- 2. CONSUMED token (replay scenario)
-- -------------------------------------------------------
INSERT INTO refresh_token_family (id, user_id, created_at, absolute_expires_at) VALUES
    ('dd000000-0000-0000-0000-000000000002',
     'bbbbbbbb-0000-0000-0000-000000000001',
     now() - INTERVAL '2 hours',
     now() + INTERVAL '7 days');

INSERT INTO refresh_token (id, family_id, token_hash, issued_at, expires_at, consumed_at) VALUES
    ('ee000000-0000-0000-0000-000000000002',
     'dd000000-0000-0000-0000-000000000002',
     'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
     now() - INTERVAL '2 hours',
     now() + INTERVAL '7 days',
     now() - INTERVAL '1 hour');

-- -------------------------------------------------------
-- 3. REVOKED family (REPLAY_ATTACK)
-- -------------------------------------------------------
INSERT INTO refresh_token_family (id, user_id, created_at, absolute_expires_at, revoked_at, revoked_reason) VALUES
    ('dd000000-0000-0000-0000-000000000003',
     'bbbbbbbb-0000-0000-0000-000000000001',
     now() - INTERVAL '3 hours',
     now() + INTERVAL '7 days',
     now() - INTERVAL '30 minutes',
     'REPLAY_ATTACK');

INSERT INTO refresh_token (id, family_id, token_hash, issued_at, expires_at, consumed_at) VALUES
    ('ee000000-0000-0000-0000-000000000003',
     'dd000000-0000-0000-0000-000000000003',
     'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
     now() - INTERVAL '3 hours',
     now() + INTERVAL '7 days',
     now() - INTERVAL '90 minutes');

-- -------------------------------------------------------
-- 4. EXPIRED family — absolute_expires_at in the past
-- -------------------------------------------------------
INSERT INTO refresh_token_family (id, user_id, created_at, absolute_expires_at) VALUES
    ('dd000000-0000-0000-0000-000000000004',
     'bbbbbbbb-0000-0000-0000-000000000001',
     now() - INTERVAL '8 days',
     now() - INTERVAL '1 day');

INSERT INTO refresh_token (id, family_id, token_hash, issued_at, expires_at) VALUES
    ('ee000000-0000-0000-0000-000000000004',
     'dd000000-0000-0000-0000-000000000004',
     'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
     now() - INTERVAL '8 days',
     now() - INTERVAL '1 day');

-- -------------------------------------------------------
-- 5. INACTIVE_USER family — owned by inactive user
-- -------------------------------------------------------
INSERT INTO refresh_token_family (id, user_id, created_at, absolute_expires_at) VALUES
    ('dd000000-0000-0000-0000-000000000005',
     'bbbbbbbb-0000-0000-0000-000000000006',
     now() - INTERVAL '1 hour',
     now() + INTERVAL '7 days');

INSERT INTO refresh_token (id, family_id, token_hash, issued_at, expires_at) VALUES
    ('ee000000-0000-0000-0000-000000000005',
     'dd000000-0000-0000-0000-000000000005',
     'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
     now() - INTERVAL '1 hour',
     now() + INTERVAL '7 days');
