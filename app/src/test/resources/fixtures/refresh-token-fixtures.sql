-- refresh-token-fixtures.sql
-- Idempotent fixture families for RefreshRotationIntegrationTest (AC-13).
-- Provides known-state families for offline branch testing. Depends on
-- identity-seed.sql having been applied first.
--
-- Family IDs use the 0x7000/0x8000 UUIDv7 test pattern for readability.

-- ---- Revoked family (reuse dedup testing) ------------------------------------
-- Owner: admin (11111111-1111-7000-8000-000000000001)
-- Already revoked — subsequent reuse calls must NOT emit a new critical event.
INSERT INTO refresh_token_family (id, user_id, created_at, expires_at, revoked_at, revoked_reason)
VALUES ('cccccccc-0000-7000-8000-000000000001',
        '11111111-1111-7000-8000-000000000001',
        NOW() - INTERVAL '1 day',
        NOW() + INTERVAL '6 days',
        NOW() - INTERVAL '1 hour',
        'REFRESH_TOKEN_REUSE')
ON CONFLICT (id) DO NOTHING;

-- ---- Expired family ----------------------------------------------------------
-- Owner: admin
-- Family lifetime has passed; rotation must return 401.
INSERT INTO refresh_token_family (id, user_id, created_at, expires_at)
VALUES ('dddddddd-0000-7000-8000-000000000001',
        '11111111-1111-7000-8000-000000000001',
        NOW() - INTERVAL '8 days',
        NOW() - INTERVAL '1 day')
ON CONFLICT (id) DO NOTHING;

-- ---- Deactivated-owner family ------------------------------------------------
-- Owner: inactive@example.local (11111111-1111-7000-8000-000000000006, active=FALSE)
-- Active family but owner has been deactivated — rotation must return 401.
INSERT INTO refresh_token_family (id, user_id, created_at, expires_at)
VALUES ('eeeeeeee-0000-7000-8000-000000000001',
        '11111111-1111-7000-8000-000000000006',
        NOW() - INTERVAL '1 day',
        NOW() + INTERVAL '6 days')
ON CONFLICT (id) DO NOTHING;

-- Note: individual token hashes for the above families are inserted programmatically
-- in RefreshRotationIntegrationTest because SHA-256 values cannot be expressed as
-- portable SQL literals without the pgcrypto extension.
