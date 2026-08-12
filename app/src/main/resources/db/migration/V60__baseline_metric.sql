-- V60: Baseline metric table for SLA KPI projections (WO-162)
--
-- Stores captured baseline values for comparison. When a row is absent for a
-- (metric_key, segment_key) pair, target_attainment is BASELINE_PENDING (BR-30).
-- No SLA threshold or improvement target is expressed here — only the captured baseline.

CREATE TABLE IF NOT EXISTS baseline_metric (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    metric_key      varchar(100) NOT NULL,
    segment_key     varchar(100) NOT NULL,
    window_key      varchar(50)  NOT NULL,
    baseline_value  numeric(20, 4) NOT NULL,
    sample_size     integer      NOT NULL,
    captured_at     timestamptz  NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT baseline_metric_pk PRIMARY KEY (id),
    CONSTRAINT baseline_metric_uq UNIQUE (metric_key, segment_key, window_key)
);

CREATE INDEX IF NOT EXISTS idx_baseline_metric_key
    ON baseline_metric (metric_key, segment_key, window_key);

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fieldservice') THEN
        GRANT SELECT, INSERT, UPDATE ON TABLE baseline_metric TO fieldservice;
    END IF;
END $$;
