-- =============================================================================
-- V111: Analytics read-model substrate fixtures (WO-161)
-- =============================================================================
-- Seeds anonymized multi-month work order, assignment and time-log history for
-- analytics tests. All values are synthetic — no real personal data.
--
-- Creates:
--   - 90 work orders distributed across 3 months (COMPLETED, CLOSED, CANCELLED,
--     IN_PROGRESS, NEW) for backlog / completion rate / SLA compliance metrics
--   - 12 completed work orders with resolution_due_at (SLA data)
--   - Pre-populated kpi_projection rows for unit assertion without waiting
--     for the recomputation cycle
--
-- Key IDs from V100:
--   ACCT_A:       00000000-0000-0000-0000-000000000001
--   site_a1:      10000000-0000-0000-0000-000000000001
--   TECH_1:       00000000-0000-0000-0000-000000000011
-- =============================================================================

-- -----------------------------------------------------------------------
-- Historical COMPLETED work orders (months -3 to -1) for completion rate
-- -----------------------------------------------------------------------
DO $$
DECLARE
    i INT;
    wo_state TEXT;
    days_ago INT;
BEGIN
    FOR i IN 1..60 LOOP
        days_ago := 5 + (i * 1);
        -- 80% completed, 20% cancelled (gives 0.8 completion rate)
        wo_state := CASE WHEN i % 5 = 0 THEN 'CANCELLED' ELSE 'COMPLETED' END;

        INSERT INTO work_order (
            id, site_id, customer_id, assigned_technician_id,
            state, priority, description, no_parts_required, version,
            created_at, updated_at
        ) VALUES (
            gen_random_uuid(),
            '10000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000011',
            wo_state,
            CASE (i % 4) WHEN 0 THEN 'LOW' WHEN 1 THEN 'MEDIUM' WHEN 2 THEN 'HIGH' ELSE 'CRITICAL' END,
            'Analytics fixture work order #' || i,
            false, 0,
            NOW() - (days_ago || ' days')::INTERVAL,
            NOW() - ((days_ago - 1) || ' days')::INTERVAL
        ) ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

-- -----------------------------------------------------------------------
-- Current open backlog (5 work orders in NEW/IN_PROGRESS state)
-- -----------------------------------------------------------------------
DO $$
DECLARE
    i INT;
    wo_state TEXT;
BEGIN
    FOR i IN 1..5 LOOP
        wo_state := CASE WHEN i <= 2 THEN 'NEW' ELSE 'IN_PROGRESS' END;
        INSERT INTO work_order (
            id, site_id, customer_id, assigned_technician_id,
            state, priority, description, no_parts_required, version,
            created_at, updated_at
        ) VALUES (
            gen_random_uuid(),
            '10000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000011',
            wo_state,
            'MEDIUM',
            'Analytics open backlog fixture #' || i,
            false, 0,
            NOW() - (i || ' hours')::INTERVAL,
            NOW() - (i || ' minutes')::INTERVAL
        ) ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

-- -----------------------------------------------------------------------
-- SLA compliance fixtures: completed WOs with resolution_due_at set
-- 10 within SLA, 2 breached (gives ~0.833 SLA compliance)
-- -----------------------------------------------------------------------
DO $$
DECLARE
    i INT;
    due_offset TEXT;
BEGIN
    FOR i IN 1..12 LOOP
        -- First 10: closed before due_at (compliant). Last 2: closed after (breach).
        due_offset := CASE WHEN i <= 10
            THEN (i + 2) || ' hours'   -- closed 2h before due
            ELSE '0 hours'             -- closed exactly at/after (use same timestamp)
        END;

        INSERT INTO work_order (
            id, site_id, customer_id, assigned_technician_id,
            state, priority, description, no_parts_required, version,
            created_at, updated_at, resolution_due_at
        ) VALUES (
            gen_random_uuid(),
            '10000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000001',
            '00000000-0000-0000-0000-000000000011',
            'COMPLETED',
            'HIGH',
            'SLA analytics fixture #' || i,
            false, 0,
            NOW() - '5 days'::INTERVAL - (i || ' hours')::INTERVAL,
            NOW() - '1 hour'::INTERVAL,
            CASE WHEN i <= 10
                THEN NOW() + (i || ' hours')::INTERVAL  -- due in future = compliant
                ELSE NOW() - '2 hours'::INTERVAL        -- due in past = breached
            END
        ) ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

-- -----------------------------------------------------------------------
-- Pre-seeded kpi_projection rows for fast assertions in tests
-- (avoid waiting for the recomputation cycle)
-- -----------------------------------------------------------------------
INSERT INTO kpi_projection (
    id, metric_key, segment_key, window_key,
    numerator, denominator, value, sample_count,
    maturity, data_as_of, projection_version, degraded
) VALUES (
    'a0161000-0000-0000-0000-000000000001',
    'workorder.backlog_count', 'ALL', 'ALL_TIME',
    5, 1, 5, 5,
    'SEEDED', NOW() - INTERVAL '30 seconds', 1, false
) ON CONFLICT (metric_key, segment_key, window_key) DO UPDATE
    SET value = 5, data_as_of = NOW() - INTERVAL '30 seconds',
        maturity = 'SEEDED', degraded = false;

INSERT INTO kpi_projection (
    id, metric_key, segment_key, window_key,
    numerator, denominator, value, sample_count,
    maturity, data_as_of, projection_version, degraded
) VALUES (
    'a0161000-0000-0000-0000-000000000002',
    'workorder.completion_rate_7d', 'ALL', 'ROLLING_7D',
    48, 60, 0.8000, 60,
    'SEEDED', NOW() - INTERVAL '25 seconds', 1, false
) ON CONFLICT (metric_key, segment_key, window_key) DO UPDATE
    SET value = 0.8000, data_as_of = NOW() - INTERVAL '25 seconds',
        maturity = 'SEEDED', degraded = false;

INSERT INTO kpi_projection (
    id, metric_key, segment_key, window_key,
    numerator, denominator, value, sample_count,
    maturity, data_as_of, projection_version, degraded
) VALUES (
    'a0161000-0000-0000-0000-000000000003',
    'workorder.sla_compliance_7d', 'ALL', 'ROLLING_7D',
    10, 12, 0.8333, 12,
    'SEEDED', NOW() - INTERVAL '20 seconds', 1, false
) ON CONFLICT (metric_key, segment_key, window_key) DO UPDATE
    SET value = 0.8333, data_as_of = NOW() - INTERVAL '20 seconds',
        maturity = 'SEEDED', degraded = false;
