-- V31__backlog_workload_guardrail.sql
-- WO-165: Backlog and workload balance guardrail projections.
-- Expand-phase only: no destructive changes to existing tables.
--
-- Adds:
--   kpi_trend_point    — immutable daily trend snapshots; once written, never updated.
--   baseline_metric    — captured reference values for guardrail direction computation.
-- Also extends the kpi_projection maturity vocabulary to accommodate NOT_MEANINGFUL
-- and guardrail direction values introduced by workload balance and future metrics.

-- ============================================================
-- 1. Extend kpi_projection maturity CHECK vocabulary
--    Original values: PROVISIONAL, STABILISING, MATURE
--    New values: NOT_MEANINGFUL (CV cannot be computed), guardrail directions
-- ============================================================
ALTER TABLE kpi_projection DROP CONSTRAINT IF EXISTS chk_kpi_maturity;
ALTER TABLE kpi_projection ADD CONSTRAINT chk_kpi_maturity
    CHECK (maturity IN (
        'PROVISIONAL', 'STABILISING', 'MATURE',
        'NOT_MEANINGFUL',
        'BASELINE_PENDING', 'WORSENED', 'IMPROVED', 'UNCHANGED'
    ));

-- ============================================================
-- 2. kpi_trend_point — immutable daily trend facts
--
-- The composite primary key (metric_key, segment_key, bucket_date) acts as a
-- unique guard so INSERT ON CONFLICT DO NOTHING never overwrites a written point.
-- Historical trend for a given day is therefore stable once committed.
-- ============================================================
CREATE TABLE kpi_trend_point (
    metric_key   VARCHAR(100)  NOT NULL,
    segment_key  VARCHAR(100)  NOT NULL,
    bucket_date  DATE          NOT NULL,
    value        NUMERIC,
    sample_count INTEGER,
    written_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_kpi_trend_point UNIQUE (metric_key, segment_key, bucket_date)
);

-- Range scan for trend API: most-recent N days for a metric+segment
CREATE INDEX idx_kpi_trend_point_metric_date
    ON kpi_trend_point (metric_key, segment_key, bucket_date DESC);

-- ============================================================
-- 3. baseline_metric — captured reference values for guardrail comparison
--
-- Populated out-of-band (operator tool or future automated capture WO).
-- WorkloadBalanceCalculator reads this table and returns BASELINE_PENDING
-- when no matching row exists, so the table can start empty.
-- ============================================================
CREATE TABLE baseline_metric (
    metric_key     VARCHAR(100)  NOT NULL,
    segment_key    VARCHAR(100)  NOT NULL,
    window_key     VARCHAR(50)   NOT NULL,
    baseline_value NUMERIC,
    captured_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_baseline_metric PRIMARY KEY (metric_key, segment_key, window_key)
);
