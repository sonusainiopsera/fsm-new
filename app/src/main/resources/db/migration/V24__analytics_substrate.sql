-- V24__analytics_substrate.sql
-- Analytics read-model substrate: KPI projection store and event idempotency guard.
--
-- These tables are DERIVED data — they are never the source of truth.
-- Envers auditing is explicitly not applied to these tables (see comment below).
--
-- No destructive changes. Expand-phase only. Safe to apply on a database already
-- containing Phase 1 and Phase 2 schema.

-- ============================================================
-- 1. Processed-event idempotency guard
-- ============================================================
-- Prevents duplicate aggregate contributions when the outbox delivers an event
-- more than once (at-least-once delivery semantics).
--
-- Retention: rows older than 30 days are pruned by a scheduled sweep; the index
-- on processed_at supports efficient window-based DELETE during purge.
CREATE TABLE processed_event (
    event_id     UUID         NOT NULL,
    metric_keys  TEXT         NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT processed_event_pk PRIMARY KEY (event_id)
);

CREATE INDEX idx_processed_event_processed_at
    ON processed_event (processed_at DESC);

-- ============================================================
-- 2. KPI projection store
-- ============================================================
-- Materialised KPI values written by the analytics worker after replica aggregation.
-- The unique constraint on (metric_key, segment_key, window_key) allows ON CONFLICT
-- upserts without a separate SELECT.
--
-- Columns:
--   metric_key         : stable dot-separated key, e.g. "wo.completion_rate"
--   segment_key        : dimension value, e.g. "PRIORITY:HIGH" or "ALL"
--   window_key         : ISO-8601 period, e.g. "P90D" or "P7D"
--   numerator          : raw numerator for rate/ratio metrics
--   denominator        : raw denominator; NULL for absolute counts
--   value              : computed value (may be NULL for zero-denominator edge case)
--   sample_count       : number of source records included in the window
--   maturity           : PROVISIONAL | STABILISING | MATURE
--   data_as_of         : timestamp of the most-recent source data included
--   projection_version : monotonic counter; incremented on each successful refresh
--   degraded           : TRUE if projection was computed in a degraded state

CREATE TABLE kpi_projection (
    id                 UUID          NOT NULL,
    metric_key         VARCHAR(100)  NOT NULL,
    segment_key        VARCHAR(100)  NOT NULL,
    window_key         VARCHAR(50)   NOT NULL,
    numerator          NUMERIC,
    denominator        NUMERIC,
    value              NUMERIC,
    sample_count       INTEGER,
    maturity           VARCHAR(20),
    data_as_of         TIMESTAMPTZ   NOT NULL,
    projection_version BIGINT        NOT NULL DEFAULT 1,
    degraded           BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT kpi_projection_pk  PRIMARY KEY (id),
    CONSTRAINT kpi_projection_uq  UNIQUE (metric_key, segment_key, window_key),
    CONSTRAINT chk_kpi_maturity   CHECK (maturity IN ('PROVISIONAL', 'STABILISING', 'MATURE'))
);

-- Range scans for freshness auditing and staleness gauge
CREATE INDEX idx_kpi_projection_data_as_of
    ON kpi_projection (data_as_of DESC);

-- Partial index supporting per-metric freshness queries
CREATE INDEX idx_kpi_projection_metric_key
    ON kpi_projection (metric_key, data_as_of DESC);

-- ============================================================
-- NOTE: Envers exclusion
-- These tables are excluded from Hibernate Envers auditing because they are
-- fully derived from immutable outbox events and the primary-table audit trail.
-- Re-deriving them from source events is always possible; auditing the derived
-- state would duplicate the storage without adding information.
-- ============================================================
