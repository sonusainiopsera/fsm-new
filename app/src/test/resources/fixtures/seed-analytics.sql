-- seed-analytics.sql
-- Idempotent fixture: multi-month anonymized KPI projections for analytics substrate tests.
-- UUIDs use prefix 'aa000000-' for easy identification and cleanup.
-- No real personal data — all values are synthetic aggregates.

-- Clear prior runs
DELETE FROM kpi_projection WHERE metric_key LIKE 'seed.%';
DELETE FROM processed_event WHERE metric_keys LIKE '%seed.%';

-- ---------------------------------------------------------------
-- KPI projections: 3 metrics × 2 segments × 2 windows = 12 rows
-- ---------------------------------------------------------------

-- Metric: seed.completion_rate (rate metric, 90-day and 7-day windows)
INSERT INTO kpi_projection (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('aa000000-0000-7000-8000-000000000001', 'seed.completion_rate', 'ALL',           'P90D', 810, 900, 0.900, 900, 'MATURE',      NOW() - INTERVAL '5 seconds',  3, false),
    ('aa000000-0000-7000-8000-000000000002', 'seed.completion_rate', 'ALL',           'P7D',   56,  60, 0.933,  60, 'STABILISING', NOW() - INTERVAL '8 seconds',  2, false),
    ('aa000000-0000-7000-8000-000000000003', 'seed.completion_rate', 'PRIORITY:HIGH', 'P90D', 190, 200, 0.950, 200, 'MATURE',      NOW() - INTERVAL '5 seconds',  3, false),
    ('aa000000-0000-7000-8000-000000000004', 'seed.completion_rate', 'PRIORITY:HIGH', 'P7D',   19,  20, 0.950,  20, 'STABILISING', NOW() - INTERVAL '8 seconds',  2, false);

-- Metric: seed.sla_compliance (rate metric)
INSERT INTO kpi_projection (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('aa000000-0000-7000-8000-000000000005', 'seed.sla_compliance', 'ALL',           'P90D', 855, 900, 0.950, 900, 'MATURE',      NOW() - INTERVAL '10 seconds', 5, false),
    ('aa000000-0000-7000-8000-000000000006', 'seed.sla_compliance', 'ALL',           'P7D',   57,  60, 0.950,  60, 'STABILISING', NOW() - INTERVAL '12 seconds', 4, false),
    ('aa000000-0000-7000-8000-000000000007', 'seed.sla_compliance', 'PRIORITY:HIGH', 'P90D', 186, 200, 0.930, 200, 'MATURE',      NOW() - INTERVAL '10 seconds', 5, false),
    ('aa000000-0000-7000-8000-000000000008', 'seed.sla_compliance', 'PRIORITY:HIGH', 'P7D',   18,  20, 0.900,  20, 'STABILISING', NOW() - INTERVAL '12 seconds', 4, false);

-- Metric: seed.backlog_count (count metric — no denominator)
INSERT INTO kpi_projection (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('aa000000-0000-7000-8000-000000000009', 'seed.backlog_count', 'ALL',           'P7D',  42, NULL, 42, 42, 'MATURE',      NOW() - INTERVAL '3 seconds',  8, false),
    ('aa000000-0000-7000-8000-000000000010', 'seed.backlog_count', 'PRIORITY:HIGH', 'P7D',   7, NULL,  7,  7, 'MATURE',      NOW() - INTERVAL '3 seconds',  8, false);

-- One projection in degraded state (simulates a replica outage window)
INSERT INTO kpi_projection (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('aa000000-0000-7000-8000-000000000011', 'seed.degraded_metric', 'ALL', 'P90D', NULL, NULL, NULL, 0, 'PROVISIONAL', NOW() - INTERVAL '120 seconds', 1, true);

-- Zero-denominator edge case: produces NULL value (no division error at application layer)
INSERT INTO kpi_projection (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('aa000000-0000-7000-8000-000000000012', 'seed.zero_denominator', 'ALL', 'P90D', 0, 0, NULL, 0, 'PROVISIONAL', NOW() - INTERVAL '20 seconds', 1, false);

-- ---------------------------------------------------------------
-- Processed-event rows matching the outbox events that produced the
-- above projections (simulates already-consumed delivery).
-- ---------------------------------------------------------------
INSERT INTO processed_event (event_id, metric_keys, processed_at)
VALUES
    ('aa000000-0000-7001-8000-000000000001', 'seed.completion_rate,seed.sla_compliance', NOW() - INTERVAL '10 minutes'),
    ('aa000000-0000-7001-8000-000000000002', 'seed.completion_rate,seed.backlog_count',  NOW() - INTERVAL '9 minutes'),
    ('aa000000-0000-7001-8000-000000000003', 'seed.sla_compliance',                      NOW() - INTERVAL '8 minutes')
ON CONFLICT (event_id) DO NOTHING;
