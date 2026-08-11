CREATE TABLE idempotency_key (
    id               UUID         PRIMARY KEY,
    idempotency_key  VARCHAR(128) NOT NULL,
    user_id          VARCHAR(255) NOT NULL,
    endpoint         VARCHAR(255) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    response_status  INTEGER,
    response_body    TEXT,
    response_headers TEXT,
    state            VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_key_user_endpoint UNIQUE (idempotency_key, user_id, endpoint),
    CONSTRAINT chk_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'NON_REPLAYABLE'))
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_key (expires_at);
