-- V7__idempotency_key.sql
-- Idempotency-key store for replay-safe mutating endpoints.
--
-- The unique constraint (idempotency_key, user_id, endpoint) scopes each key to
-- one principal and one endpoint so:
--   - Two principals cannot probe each other's keys
--   - The same key can be reused across different endpoints independently
--
-- Columns:
--   idempotency_key  : client-supplied opaque key (16–128 chars)
--   user_id          : authenticated principal (from JWT sub); scopes the key
--   endpoint         : "METHOD /path" normalised endpoint string
--   request_hash     : SHA-256 hex of (method || NL || path || NL || body bytes); raw body never stored
--   state            : IN_PROGRESS (claim held), COMPLETED (storable response), NON_REPLAYABLE (oversize body)
--   response_status  : HTTP status code of the completed response
--   response_body    : bounded captured response body (TEXT, null for NON_REPLAYABLE)
--   response_headers : JSON object of allow-listed response headers
--   lease_expires_at : IN_PROGRESS rows older than this may be reclaimed by a retry
--   expires_at       : row eligible for purge after this time (COMPLETED: 24 h from completion)

CREATE TABLE idempotency_key (
    id               UUID         NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    user_id          UUID         NOT NULL,
    endpoint         VARCHAR(500) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    state            VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    response_status  INTEGER,
    response_body    TEXT,
    response_headers TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,

    PRIMARY KEY (id),

    -- Core uniqueness scope: one key per (principal + endpoint) pair
    CONSTRAINT uq_idempotency_key_user_endpoint
        UNIQUE (idempotency_key, user_id, endpoint),

    CONSTRAINT chk_idempotency_state
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'NON_REPLAYABLE'))
);

-- Partial index for the purge job — only scans COMPLETED rows with expires_at set
CREATE INDEX idx_idempotency_key_expires_at
    ON idempotency_key(expires_at)
    WHERE state = 'COMPLETED' OR state = 'NON_REPLAYABLE';

-- Index to speed up the stale IN_PROGRESS reclaim query
CREATE INDEX idx_idempotency_key_lease
    ON idempotency_key(lease_expires_at)
    WHERE state = 'IN_PROGRESS';
