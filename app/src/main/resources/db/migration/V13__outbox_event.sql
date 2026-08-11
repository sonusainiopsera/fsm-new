-- V13__outbox_event.sql
-- Transactional outbox table for domain events (WO-004).
--
-- One transaction writes the domain row, the Envers revision, and an outbox_event row
-- atomically, making it structurally impossible to publish an event describing a change
-- that rolled back, or to have a state change with no downstream event.
--
-- The WO-005 poller reads unpublished rows using FOR UPDATE SKIP LOCKED and marks them
-- published_at after successful broker delivery.
--
-- Columns:
--   event_id       : UUIDv7 primary key; monotonic, non-guessable; consumer deduplication key
--   event_type     : stable event type name (e.g. "WorkOrderStateChanged")
--   aggregate_type : stable aggregate type name (e.g. "WorkOrder")
--   aggregate_id   : UUID of the aggregate root that produced the event
--   payload        : JSON payload built from a purpose-built payload record; never a raw entity
--   trace_id       : request trace-id for correlation with audit revisions and log lines
--   actor_user_id  : authenticated actor UUID; NULL for system-initiated events
--   created_at     : row creation time (also used as secondary sort key in the drain index)
--   published_at   : NULL until the poller confirms broker delivery; used in the drain index
--   attempt_count  : incremented by the poller on each delivery attempt (for dead-lettering in WO-005)
--   last_error     : most recent delivery error message; retained for dead-letter diagnostics

CREATE TABLE outbox_event (
    event_id       UUID         NOT NULL,
    event_type     TEXT         NOT NULL,
    aggregate_type TEXT         NOT NULL,
    aggregate_id   UUID         NOT NULL,
    payload        JSONB        NOT NULL,
    trace_id       TEXT,
    actor_user_id  UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempt_count  INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT,

    PRIMARY KEY (event_id)
);

-- Partial drain index: only covers unpublished rows (published_at IS NULL).
-- Shrinks to near-zero once the queue is fully drained.
-- Ordered published_at NULLS FIRST then created_at for oldest-first dispatch order.
CREATE INDEX idx_outbox_event_drain
    ON outbox_event (published_at NULLS FIRST, created_at)
    WHERE published_at IS NULL;

-- Supporting index for aggregate-level queries (e.g. "all events for work order X")
CREATE INDEX idx_outbox_event_aggregate
    ON outbox_event (aggregate_type, aggregate_id);
