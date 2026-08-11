-- V15__outbox_drain.sql
-- Adds drain-support columns to outbox_event, creates processed_event for consumer
-- idempotency, and creates scheduler_lock for single-replica periodic sweeps.
-- Expand-only: no DROP, no tightening of existing NOT NULL columns.

-- ============================================================
-- outbox_event — drain retry columns
-- ============================================================

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS dead_lettered_at  TIMESTAMPTZ;

-- Drop the old partial index (created by V11) that does not cover next_attempt_at / dead_lettered_at,
-- then replace it with the claim-predicate-exact index WO-005 poller depends on.
DROP INDEX IF EXISTS idx_outbox_event_drain;

CREATE INDEX idx_outbox_event_drain
    ON outbox_event (created_at)
    WHERE published_at IS NULL
      AND dead_lettered_at IS NULL
      AND next_attempt_at <= NOW();

-- ============================================================
-- processed_event — consumer-side idempotency
-- ============================================================
CREATE TABLE processed_event (
    event_id      UUID         NOT NULL,
    consumer_name TEXT         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer_name)
);

-- ============================================================
-- scheduler_lock — single-replica periodic sweep guard
-- ============================================================
CREATE TABLE scheduler_lock (
    lock_name   TEXT        NOT NULL,
    holder      TEXT        NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_scheduler_lock PRIMARY KEY (lock_name)
);
