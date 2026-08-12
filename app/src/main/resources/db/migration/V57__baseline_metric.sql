-- V57__baseline_metric.sql
-- WO-162: Adds baseline_metric table for SLA KPI baseline-capture-then-improve pattern.
-- A captured baseline row exists once per (metric_key, segment_key) after the first
-- 30-day production baseline window. Until then, projections report BASELINE_PENDING
-- per BR-30 labelling requirements.

CREATE TABLE baseline_metric (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    metric_key     VARCHAR(100) NOT NULL,
    segment_key    VARCHAR(100) NOT NULL,
    baseline_value NUMERIC      NOT NULL,
    sample_size    INTEGER      NOT NULL DEFAULT 0,
    captured_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_baseline_metric    PRIMARY KEY (id),
    CONSTRAINT uq_baseline_metric_key UNIQUE (metric_key, segment_key)
);

-- Supports resolution of all baselines for a given metric in one scan
CREATE INDEX idx_baseline_metric_metric_key
    ON baseline_metric (metric_key);
