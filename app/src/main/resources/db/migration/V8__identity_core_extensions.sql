-- =============================================================================
-- V8: Identity core extensions to app_user
-- =============================================================================
-- Expand phase: adds columns and relaxes constraints to accommodate future
-- federated authentication (Q12 forward-compatibility).
-- No destructive changes in this migration.
--
-- Changes:
--   1. Widen password_hash from VARCHAR(72) to VARCHAR(256) to hold algorithm
--      prefixes such as "$argon2id$v=19$..." without silent truncation.
--   2. Make password_hash nullable so federated users (external_subject) can
--      be created without a local credential.
--   3. Add external_subject (nullable, reserved for future IdP federation).
--   4. Replace case-sensitive UNIQUE constraint on email with a functional
--      case-insensitive unique index on lower(email) so mixed-case registration
--      attempts collide on the canonical account.
-- =============================================================================

-- 1. Widen password_hash to accommodate algorithm prefixes (expand phase)
ALTER TABLE app_user ALTER COLUMN password_hash TYPE VARCHAR(256);

-- 2. Make password_hash nullable (federated users have no local credential)
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;

-- 3. Reserve external_subject for future identity federation (e.g. OIDC sub claim)
--    Q12 (self-registration vs invitation vs federation) is unratified — this column
--    allows any strategy to be added later without a destructive migration.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS external_subject VARCHAR(500);

COMMENT ON COLUMN app_user.external_subject IS 'Reserved — OIDC sub/federation subject, null until Q12 is ratified';
COMMENT ON COLUMN app_user.password_hash    IS 'CONFIDENTIAL – BCrypt/Argon2 hash, nullable for federated users; width 256 for algorithm prefix headroom';

-- 4. Replace case-sensitive email uniqueness with a case-insensitive functional index.
--    Two accounts registering the same email in different letter cases must collide.
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS uq_app_user_email;
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_user_email_lower
    ON app_user (lower(email));
