-- =============================================================================
-- V10: Refresh-token family and token tables
-- =============================================================================
-- Refresh tokens use a family model: one family per login session, multiple
-- tokens within a family (rotation chain). Revoking a family invalidates all
-- tokens in it (e.g. on logout, suspicious replay detection).
--
-- Security constraints:
--   - Only the SHA-256 hex hash (64 chars) of the opaque handle is stored.
--     The plaintext handle is never written to the database.
--   - token_hash has a unique index to prevent replay of a consumed token.
--   - ON DELETE CASCADE from family: revoking a family removes all tokens.
-- =============================================================================

CREATE TABLE refresh_token_family (
    id              UUID          NOT NULL,
    user_id         UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(255),

    CONSTRAINT  pk_refresh_token_family      PRIMARY KEY (id),
    CONSTRAINT  fk_rtf_user                  FOREIGN KEY (user_id)
                    REFERENCES app_user(id)  ON DELETE CASCADE
);

CREATE INDEX idx_refresh_token_family_user_id ON refresh_token_family(user_id);

COMMENT ON COLUMN refresh_token_family.revoked_reason IS 'Human-readable reason (LOGOUT, REPLAY_ATTACK, ADMIN_REVOKE, EXPIRED)';

-- =============================================================================
-- refresh_token: individual token record within a family.
-- =============================================================================

CREATE TABLE refresh_token (
    id              UUID          NOT NULL,
    family_id       UUID          NOT NULL,
    token_hash      VARCHAR(64)   NOT NULL,  -- SHA-256 hex, never the plaintext handle
    issued_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    consumed_at     TIMESTAMPTZ,             -- set when this token was exchanged for a new one

    CONSTRAINT  pk_refresh_token              PRIMARY KEY (id),
    CONSTRAINT  fk_rt_family                  FOREIGN KEY (family_id)
                    REFERENCES refresh_token_family(id) ON DELETE CASCADE,
    CONSTRAINT  uq_refresh_token_hash         UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_hash      ON refresh_token(token_hash);
CREATE INDEX idx_refresh_token_family_id ON refresh_token(family_id);

COMMENT ON COLUMN refresh_token.token_hash IS 'CONFIDENTIAL – SHA-256 hex of the opaque handle; plaintext handle must never be persisted';
COMMENT ON COLUMN refresh_token.consumed_at IS 'Non-null means this token was rotated; a second use after consumption is a replay attack';
