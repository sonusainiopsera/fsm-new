-- Idempotency key store (WO-008)
-- Ensures exactly-once execution for mutating HTTP requests from mobile clients on flaky connections.
-- Each row claims a (idempotency_key, user_id, endpoint) slot for 24 hours.

CREATE TABLE idempotency_key (
    id               UUID          NOT NULL,
    key              VARCHAR(128)  NOT NULL,
    user_id          VARCHAR(255)  NOT NULL,
    endpoint         VARCHAR(512)  NOT NULL,
    request_hash     CHAR(64)      NOT NULL,
    state            VARCHAR(20)   NOT NULL DEFAULT 'IN_PROGRESS',
    response_status  SMALLINT,
    response_body    TEXT,
    response_headers TEXT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_idempotency_key PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_key UNIQUE (key, user_id, endpoint),
    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'NON_REPLAYABLE'))
);

-- Fast look-up for the purge job deleting expired rows in bounded batches
CREATE INDEX idx_idempotency_key_expires ON idempotency_key (expires_at);

-- Composite index to accelerate the primary look-up path in the filter
CREATE INDEX idx_idempotency_key_lookup ON idempotency_key (key, user_id, endpoint);
