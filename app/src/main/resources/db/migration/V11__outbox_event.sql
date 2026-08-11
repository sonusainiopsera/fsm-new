-- V11__outbox_event.sql
-- Transactional outbox: every domain state change appends a row here atomically.
-- WO-005 poller drains rows by setting published_at.
--
-- Columns:
--   event_id       : UUIDv7 PK — consumer deduplication key (at-least-once delivery)
--   event_type     : stable string constant identifying the event schema (e.g. WORK_ORDER_ASSIGNED)
--   aggregate_type : entity type (e.g. WORK_ORDER, TECHNICIAN)
--   aggregate_id   : UUID of the aggregate root
--   payload        : jsonb — purpose-built payload record, never a raw entity serialisation
--   trace_id       : MDC traceId from the originating request (for log correlation)
--   actor_user_id  : authenticated user UUID (for event-to-revision correlation)
--   created_at     : insertion timestamp (UTC)
--   published_at   : set by WO-005 poller once delivered; null = unpublished
--   attempt_count  : incremented by WO-005 on each delivery attempt
--   last_error     : last delivery error message for debugging (dead-letter path)

CREATE TABLE outbox_event (
    event_id       UUID         NOT NULL,
    event_type     TEXT         NOT NULL,
    aggregate_type TEXT         NOT NULL,
    aggregate_id   UUID         NOT NULL,
    payload        JSONB        NOT NULL,
    trace_id       TEXT,
    actor_user_id  UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ,
    attempt_count  INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT,
    CONSTRAINT outbox_event_pk PRIMARY KEY (event_id)
);

-- Drain-optimised partial index: only unpublished rows appear in this index.
-- The index shrinks to near-zero when the outbox queue is fully drained.
-- WO-005 poller query: SELECT ... FROM outbox_event WHERE published_at IS NULL ORDER BY created_at
CREATE INDEX idx_outbox_event_drain
    ON outbox_event (created_at)
    WHERE published_at IS NULL;

-- Supporting index for per-aggregate replay and correlation
CREATE INDEX idx_outbox_event_aggregate
    ON outbox_event (aggregate_type, aggregate_id);
