-- =============================================================================
-- V8: Identity model extensions — role_assignment, refresh token families,
-- and forward-compatibility adjustments to app_user for Q12
-- (self-registration vs invitation vs federation — unratified).
--
-- IMPORTANT: password_hash is made nullable here to accommodate future
-- federated users who authenticate via external_subject rather than a local
-- credential. The column is NOT removed; code paths requiring a password MUST
-- check nullability explicitly.
--
-- Invitation and federation tables are explicitly deferred pending Q12
-- ratification; this migration reserves external_subject and ensures no
-- destructive change will be required when either path is adopted.
-- =============================================================================

-- ── app_user extensions ───────────────────────────────────────────────────────

-- Q12 forward-compat: password_hash becomes nullable (federated users have none).
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;

-- display_name: preferred display name (may differ from full_name).
ALTER TABLE app_user ADD COLUMN display_name VARCHAR(200);

-- external_subject: reserved for future federation (e.g. OIDC sub claim). Nullable.
ALTER TABLE app_user ADD COLUMN external_subject VARCHAR(400);

-- Replace case-sensitive unique constraint with a case-insensitive functional index
-- so upper/lower-case variants of the same address collide correctly.
ALTER TABLE app_user DROP CONSTRAINT uq_app_user_email;
CREATE UNIQUE INDEX uq_app_user_email_ci ON app_user (lower(email));

COMMENT ON COLUMN app_user.password_hash IS
    'BCrypt hash (60 chars) or algorithm-prefixed variant. Nullable for federated users. RESTRICTED: never log.';
COMMENT ON COLUMN app_user.external_subject IS
    'Reserved for OIDC sub claim or federation provider subject. Deferred pending Q12 ratification.';

-- ── app_user_aud extensions ────────────────────────────────────────────────────
-- Mirror the new columns into the audit table so Hibernate ddl-auto=validate succeeds.
-- Audit columns are nullable regardless of base-table nullability (Envers convention).
ALTER TABLE app_user_aud ADD COLUMN display_name    VARCHAR(200);
ALTER TABLE app_user_aud ADD COLUMN external_subject VARCHAR(400);

-- ── role_assignment ────────────────────────────────────────────────────────────
-- Stores the five ratified roles. Role vocabulary enforced twice: Java enum AppRole
-- and this CHECK constraint so an out-of-vocabulary role is rejected even by scripts.
-- ON DELETE RESTRICT preserves grant history when an app_user row would be removed.

CREATE TABLE role_assignment (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    role_name   VARCHAR(50)  NOT NULL,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    granted_by  UUID         REFERENCES app_user (id),
    CONSTRAINT uq_role_assignment_user_role UNIQUE (user_id, role_name),
    CONSTRAINT chk_role_name CHECK (role_name IN ('ADMIN', 'DISPATCHER', 'TECHNICIAN', 'MANAGER', 'CUSTOMER'))
);

CREATE INDEX idx_role_assignment_user_id ON role_assignment (user_id);

-- ── refresh_token_family ──────────────────────────────────────────────────────
-- Groups all tokens belonging to one logical session. Revoking the family
-- invalidates all outstanding refresh tokens issued under it.

CREATE TABLE refresh_token_family (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(200)
);

CREATE INDEX idx_refresh_token_family_user_id ON refresh_token_family (user_id);

-- ── refresh_token ──────────────────────────────────────────────────────────────
-- RESTRICTED: token_hash is the SHA-256 hex of the opaque bearer handle.
-- The plaintext handle MUST NOT be persisted. A database disclosure yields
-- nothing replayable because only the hash is stored.

CREATE TABLE refresh_token (
    id          UUID         PRIMARY KEY,
    family_id   UUID         NOT NULL REFERENCES refresh_token_family (id) ON DELETE CASCADE,
    -- RESTRICTED: SHA-256 hex (64 chars) of the opaque handle. Never store plaintext.
    token_hash  VARCHAR(64)  NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_family_id ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_expires_at ON refresh_token (expires_at);
