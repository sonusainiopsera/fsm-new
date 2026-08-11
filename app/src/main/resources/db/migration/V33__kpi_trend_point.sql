-- =============================================================================
-- V33: KPI trend point table (WO-165)
-- =============================================================================
-- Creates kpi_trend_point for daily point-in-time backlog history.
-- Trend points are immutable once written: the unique constraint on
-- (metric_key, segment_key, bucket_date) prevents overwrites so a
-- historical day's backlog cannot change retroactively.
-- Expand-phase only — no destructive changes to existing tables.
-- =============================================================================

CREATE TABLE IF NOT EXISTS kpi_trend_point (
    id           UUID        NOT NULL PRIMARY KEY,
    metric_key   VARCHAR(100) NOT NULL,
    segment_key  VARCHAR(100) NOT NULL DEFAULT 'ALL',
    bucket_date  DATE         NOT NULL,
    value        NUMERIC(20,4) NOT NULL,
    sample_count INTEGER       NOT NULL DEFAULT 0,
    written_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_kpi_trend_point UNIQUE (metric_key, segment_key, bucket_date)
);

CREATE INDEX IF NOT EXISTS idx_kpi_trend_metric_date
    ON kpi_trend_point (metric_key, segment_key, bucket_date DESC);

-- -----------------------------------------------------------------------
-- Grants
-- -----------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') THEN
        GRANT SELECT, INSERT ON TABLE kpi_trend_point TO fieldservice;
    END IF;
END
$$;
