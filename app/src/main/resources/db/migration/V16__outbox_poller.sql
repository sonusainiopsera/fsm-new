-- WO-005: Outbox poller – retry columns, processed_event idempotency, and scheduler_lock.

-- Retry tracking columns: next_attempt_at drives the claim window; dead_lettered_at
-- excludes poison events permanently from the claim query without deleting them.
ALTER TABLE outbox_event
    ADD COLUMN next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

-- Replace the V13 partial drain index with one that exactly matches the claim predicate
-- (published_at IS NULL AND dead_lettered_at IS NULL AND next_attempt_at <= now()).
-- oldest-first ordering aligns with the created_at sort key in the poller query.
DROP INDEX idx_outbox_event_drain;
CREATE INDEX idx_outbox_event_drain
    ON outbox_event (next_attempt_at, created_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

-- Consumer idempotency: records which (event, consumer) pairs have been processed.
-- Handlers INSERT (event_id, consumer_name) before side effects; a unique violation
-- means the event was already processed by that consumer and the handler returns early.
-- This table is inside the claim transaction so the insert is atomic with the dispatch.
CREATE TABLE processed_event (
    event_id      UUID        NOT NULL,
    consumer_name TEXT        NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_name)
);

-- Distributed lock: lease-based single-replica election for periodic sweeps.
-- The conditional INSERT … ON CONFLICT … WHERE uses database now() exclusively
-- so clock skew between replicas cannot produce two simultaneous leaders.
-- A missed commit or crash leaves the lease valid until expires_at, at which point
-- any replica can reacquire by satisfying the WHERE clause.
CREATE TABLE scheduler_lock (
    lock_name   TEXT        NOT NULL,
    holder      TEXT        NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (lock_name)
);
