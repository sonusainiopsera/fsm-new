-- =============================================================================
-- V137: KPI drill-down test fixtures (WO-168)
-- =============================================================================
-- Provides additional work orders beyond the V108 set for drill-down tests.
-- All IDs are synthetic — no real personal data.
--
-- Key IDs from V100__test_fixtures.sql:
--   ACCT_A:   00000000-0000-0000-0000-000000000001
--   ACCT_B:   00000000-0000-0000-0000-000000000002
--   TECH_1:   00000000-0000-0000-0000-000000000011  (MANAGER scope)
--   TECH_2:   00000000-0000-0000-0000-000000000012  (MANAGER scope)
--   site_a1:  10000000-0000-0000-0000-000000000001
--   site_a2:  10000000-0000-0000-0000-000000000002
--   site_b1:  10000000-0000-0000-0000-000000000003
--
-- Provides:
--   - 60 COMPLETED work orders (last 30 days) spanning both accounts,
--     all sites, both technicians — for SLA / FTF / utilization drill-down
--   - 10 ON_HOLD work orders for BACKLOG_ON_HOLD_COUNT drill-down
--   - 5 IN_PROGRESS at-risk work orders (deadline in past) for SLA_BREACH_COUNT
--   - Uses IDs in the 4d000000-... range to avoid collisions with V108 (4c000000-...)
-- =============================================================================

DO $$
DECLARE
    i      INT;
    tech   UUID;
    acct   UUID;
    site   UUID;
    state  TEXT;
    days_ago INT;
BEGIN
    -- 60 COMPLETED work orders spread over last 28 days (covers THIRTY_DAYS window)
    FOR i IN 1..60 LOOP
        days_ago := (i % 28) + 1;
        tech := CASE WHEN i % 2 = 0
                    THEN '00000000-0000-0000-0000-000000000011'::UUID
                    ELSE '00000000-0000-0000-0000-000000000012'::UUID END;
        acct := CASE WHEN i % 3 = 0
                    THEN '00000000-0000-0000-0000-000000000002'::UUID
                    ELSE '00000000-0000-0000-0000-000000000001'::UUID END;
        site := CASE
                    WHEN i % 3 = 0 THEN '10000000-0000-0000-0000-000000000003'::UUID
                    WHEN i % 2 = 0 THEN '10000000-0000-0000-0000-000000000002'::UUID
                    ELSE '10000000-0000-0000-0000-000000000001'::UUID
                END;

        INSERT INTO work_order (
            id, site_id, customer_id, assigned_technician_id,
            state, priority, description, no_parts_required, version,
            created_at, updated_at
        ) VALUES (
            ('4d000000-0000-0000-' || LPAD(i::TEXT, 4, '0') || '-000000000001')::UUID,
            site, acct, tech,
            'COMPLETED',
            CASE WHEN i % 3 = 0 THEN 'HIGH' WHEN i % 3 = 1 THEN 'MEDIUM' ELSE 'LOW' END,
            'Drill-down fixture COMPLETED #' || i, true, 0,
            NOW() - (days_ago || ' days')::INTERVAL,
            NOW() - ((days_ago - 1) || ' days')::INTERVAL
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- 10 ON_HOLD work orders for BACKLOG_ON_HOLD_COUNT drill-down
    FOR i IN 1..10 LOOP
        INSERT INTO work_order (
            id, site_id, customer_id, assigned_technician_id,
            state, priority, description, no_parts_required, version,
            created_at, updated_at
        ) VALUES (
            ('4d000000-0000-0000-' || LPAD(i::TEXT, 4, '0') || '-000000000002')::UUID,
            '10000000-0000-0000-0000-000000000001'::UUID,
            '00000000-0000-0000-0000-000000000001'::UUID,
            '00000000-0000-0000-0000-000000000011'::UUID,
            'ON_HOLD',
            CASE WHEN i % 2 = 0 THEN 'HIGH' ELSE 'MEDIUM' END,
            'Drill-down fixture ON_HOLD #' || i, false, 0,
            NOW() - (i || ' days')::INTERVAL,
            NOW() - ((i - 1) || ' days')::INTERVAL
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- 5 IN_PROGRESS work orders with past SLA deadline (at-risk, for SLA_BREACH_COUNT)
    FOR i IN 1..5 LOOP
        INSERT INTO work_order (
            id, site_id, customer_id, assigned_technician_id,
            state, priority, description, no_parts_required, version,
            sla_deadline, created_at, updated_at
        ) VALUES (
            ('4d000000-0000-0000-' || LPAD(i::TEXT, 4, '0') || '-000000000003')::UUID,
            '10000000-0000-0000-0000-000000000001'::UUID,
            '00000000-0000-0000-0000-000000000001'::UUID,
            '00000000-0000-0000-0000-000000000011'::UUID,
            'IN_PROGRESS', 'HIGH',
            'Drill-down at-risk fixture #' || i, false, 0,
            NOW() - '2 days'::INTERVAL,
            NOW() - (i || ' hours')::INTERVAL,
            NOW()
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;
END;
$$;
