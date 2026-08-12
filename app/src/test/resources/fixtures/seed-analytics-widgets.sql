-- seed-analytics-widgets.sql
-- Per-test fixture for DashboardWidgetControllerIT.
-- UUIDs use prefix 'bb000000-' to avoid collisions with seed-analytics.sql.
-- All values are synthetic; no personal data.

-- Clean up this fixture's rows before inserting (idempotent in case of prior failure)
DELETE FROM kpi_projection WHERE id::text LIKE 'bb000000%';

-- ---------------------------------------------------------------
-- sla.compliance.rate — ALL segment — P30D (THIRTY_DAYS)
-- ---------------------------------------------------------------
INSERT INTO kpi_projection
    (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('bb000000-0000-7166-8000-000000000001',
     'sla.compliance.rate', 'ALL', 'P30D',
     855, 900, 0.9500, 900, 'MATURED',
     NOW() - INTERVAL '15 seconds', 7, false);

-- backlog.open.count — ALL segment — P30D
INSERT INTO kpi_projection
    (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('bb000000-0000-7166-8000-000000000002',
     'backlog.open.count', 'ALL', 'P30D',
     42, NULL, 42, 42, 'MATURED',
     NOW() - INTERVAL '20 seconds', 5, false);

-- workforce.utilization.rate — ALL segment — P30D
INSERT INTO kpi_projection
    (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('bb000000-0000-7166-8000-000000000003',
     'workforce.utilization.rate', 'ALL', 'P30D',
     780, 1000, 0.7800, 1000, 'MATURED',
     NOW() - INTERVAL '10 seconds', 3, false);

-- degraded metric — sla.breach.count — ALL segment — P30D (degraded=true, no value)
INSERT INTO kpi_projection
    (id, metric_key, segment_key, window_key, numerator, denominator, value, sample_count, maturity, data_as_of, projection_version, degraded)
VALUES
    ('bb000000-0000-7166-8000-000000000010',
     'sla.breach.count', 'ALL', 'P30D',
     NULL, NULL, NULL, 0, 'PROVISIONAL',
     '1970-01-01T00:00:00Z', 1, true);

-- second version of sla.compliance.rate used in "changed ETag" tests
-- (inserted explicitly in the test via JDBC update on projection_version)
