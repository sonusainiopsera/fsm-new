-- =============================================================================
-- V27: Analytics read-model substrate (WO-161)
-- =============================================================================
-- Creates:
--   kpi_projection  — versioned KPI snapshot rows, one per (metric, segment, window)
--   analytics_processed_event — idempotency guard scoped to the analytics consumer
--
-- Analytics tables are intentionally EXCLUDED from Envers auditing because they
-- are derived read-model projections; the source of truth is the outbox event history
-- and the underlying domain tables. Auditing derived rows would create redundant
-- noise and triple the write amplification on every projection refresh.
--
-- Expand-phase only — no destructive changes to existing tables.
-- =============================================================================

-- -----------------------------------------------------------------------
-- kpi_projection: one row per (metric_key, segment_key, window_key).
-- Projection rows are upserted atomically; projection_version is bumped
-- on every successful refresh so callers can detect staleness via ETags.
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kpi_projection (
    id                  UUID         NOT NULL PRIMARY KEY,
    metric_key          VARCHAR(100) NOT NULL,
    segment_key         VARCHAR(100) NOT NULL DEFAULT 'ALL',
    window_key          VARCHAR(50)  NOT NULL DEFAULT 'ROLLING_7D',
    numerator           NUMERIC(20,4),
    denominator         NUMERIC(20,4),
    value               NUMERIC(20,4),
    sample_count        INTEGER      NOT NULL DEFAULT 0,
    maturity            VARCHAR(20)  NOT NULL DEFAULT 'CURRENT',
    data_as_of          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    projection_version  BIGINT       NOT NULL DEFAULT 1,
    degraded            BOOLEAN      NOT NULL DEFAULT false,
    degraded_reason     TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_kpi_metric_segment_window UNIQUE (metric_key, segment_key, window_key),
    CONSTRAINT chk_kpi_maturity CHECK (maturity IN ('CURRENT', 'HISTORICAL', 'SEEDED')),
    CONSTRAINT chk_kpi_value_non_null_when_not_degraded
        CHECK (degraded = true OR value IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_kpi_projection_data_as_of
    ON kpi_projection (data_as_of DESC);

CREATE INDEX IF NOT EXISTS idx_kpi_projection_metric_key
    ON kpi_projection (metric_key, segment_key);

-- -----------------------------------------------------------------------
-- analytics_processed_event: idempotency guard for the analytics consumer.
-- Separate from the platform processed_event table so analytics consumer
-- retention can be managed independently (bounded window).
-- -----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS analytics_processed_event (
    event_id        UUID        NOT NULL PRIMARY KEY,
    metric_keys     TEXT        NOT NULL,  -- comma-separated metric keys touched
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_analytics_processed_event_processed_at
    ON analytics_processed_event (processed_at);

-- -----------------------------------------------------------------------
-- Least-privilege grants: analytics tables are read-write for the app role
-- (projection upserts must succeed). UPDATE is permitted because projections
-- are mutable; the ledger append-only contract applies only to stock_ledger.
-- -----------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE kpi_projection TO fieldservice;
        GRANT SELECT, INSERT, DELETE ON TABLE analytics_processed_event TO fieldservice;
    END IF;
END
$$;
