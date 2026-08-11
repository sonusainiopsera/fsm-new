-- =============================================================================
-- V112: Stock ledger 30-day synthetic history fixtures (WO-150)
-- =============================================================================
-- Creates:
--   - 30 days of synthetic stock_ledger entries across two parts and two locations
--   - One deliberately-mismatched balance row for discrepancy detection test
--   - Three completed work orders in the 24-hour window:
--       WO-COMP-1: has ledger entries (fully logged)
--       WO-COMP-2: has no_parts_required=true (no ledger needed)
--       WO-COMP-3: no ledger entries, no flag (incomplete → lowers completeness)
--   - Transfer pair with shared correlation_id to test paired-entry logic
--
-- Key IDs reused from V100:
--   part PN-001:       50000000-0000-0000-0000-000000000001
--   part PN-002:       50000000-0000-0000-0000-000000000002
--   TECH_1 Van A:      60000000-0000-0000-0000-000000000011  (vehicle location)
--   Warehouse-A:       60000000-0000-0000-0000-000000000001  (warehouse location)
--   TECH_1 user:       aaaaaaaa-0000-0000-0000-000000000011
--   ACCT_A:            00000000-0000-0000-0000-000000000001
--   site_a1:           10000000-0000-0000-0000-000000000001
-- =============================================================================

-- -----------------------------------------------------------------------
-- 30-day RECEIPT entries for PN-001 at Warehouse-A (day -30 to day -1)
-- -----------------------------------------------------------------------
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..30 LOOP
        INSERT INTO stock_ledger (
            id, part_id, location_id, quantity_delta, movement_type,
            from_location_id, actor_user_id, occurred_at, created_at
        ) VALUES (
            gen_random_uuid(),
            '50000000-0000-0000-0000-000000000001',  -- PN-001
            '60000000-0000-0000-0000-000000000001',  -- Warehouse-A
            2,
            'REPLENISHMENT',
            NULL,
            'aaaaaaaa-0000-0000-0000-000000000011',  -- TECH_1
            NOW() - (i || ' days')::INTERVAL,
            NOW() - (i || ' days')::INTERVAL
        ) ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

-- -----------------------------------------------------------------------
-- 30-day CONSUMPTION entries for PN-002 at TECH_1 Van A (day -30 to -1)
-- -----------------------------------------------------------------------
DO $$
DECLARE
    i INT;
BEGIN
    FOR i IN 1..30 LOOP
        INSERT INTO stock_ledger (
            id, part_id, location_id, quantity_delta, movement_type,
            from_location_id, actor_user_id, occurred_at, created_at
        ) VALUES (
            gen_random_uuid(),
            '50000000-0000-0000-0000-000000000002',  -- PN-002
            '60000000-0000-0000-0000-000000000011',  -- TECH_1 Van A
            -1,
            'CONSUMPTION',
            '60000000-0000-0000-0000-000000000011',
            'aaaaaaaa-0000-0000-0000-000000000011',
            NOW() - (i || ' days')::INTERVAL,
            NOW() - (i || ' days')::INTERVAL
        ) ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

-- -----------------------------------------------------------------------
-- Transfer pair: PN-001 Warehouse-A → TECH_1 Van A
-- Both entries share the same correlation_id (tests paired-entry logic)
-- -----------------------------------------------------------------------
INSERT INTO stock_ledger (
    id, part_id, location_id, quantity_delta, movement_type,
    from_location_id, to_location_id, actor_user_id,
    correlation_id, occurred_at, created_at
) VALUES (
    'e1000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',  -- PN-001
    '60000000-0000-0000-0000-000000000001',  -- Warehouse-A (out)
    -5,
    'TRANSFER_OUT',
    '60000000-0000-0000-0000-000000000001',
    '60000000-0000-0000-0000-000000000011',
    'aaaaaaaa-0000-0000-0000-000000000011',
    'f0000000-cafe-0000-0000-000000000001',  -- shared correlation_id
    NOW() - INTERVAL '2 hours',
    NOW() - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;

INSERT INTO stock_ledger (
    id, part_id, location_id, quantity_delta, movement_type,
    from_location_id, to_location_id, actor_user_id,
    correlation_id, occurred_at, created_at
) VALUES (
    'e1000000-0000-0000-0000-000000000002',
    '50000000-0000-0000-0000-000000000001',  -- PN-001
    '60000000-0000-0000-0000-000000000011',  -- TECH_1 Van A (in)
    5,
    'TRANSFER_IN',
    '60000000-0000-0000-0000-000000000001',
    '60000000-0000-0000-0000-000000000011',
    'aaaaaaaa-0000-0000-0000-000000000011',
    'f0000000-cafe-0000-0000-000000000001',  -- same correlation_id
    NOW() - INTERVAL '2 hours',
    NOW() - INTERVAL '2 hours'
) ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------
-- Deliberately mismatched balance row (for discrepancy tests)
-- PN-002 at Warehouse-A: balance says 100 but ledger has no history there.
-- Reconciliation sweep should flag this as a discrepancy.
-- -----------------------------------------------------------------------
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, version)
VALUES (
    'd0150000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000002',  -- PN-002
    '60000000-0000-0000-0000-000000000001',  -- Warehouse-A (no ledger history for this pair)
    100,
    0
) ON CONFLICT (part_id, location_id) DO UPDATE
    SET quantity_on_hand = 100;

-- -----------------------------------------------------------------------
-- Completed work orders for completeness metric tests (closed within 24h)
-- -----------------------------------------------------------------------

-- WO-COMP-1: has parts consumed → fully logged
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id, state,
                        priority, description, no_parts_required, version, updated_at)
VALUES (
    'c0000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    'COMPLETED', 'MEDIUM',
    'WO-150 completeness fixture: WO with parts consumed',
    false, 0,
    NOW() - INTERVAL '1 hour'
) ON CONFLICT DO NOTHING;

INSERT INTO stock_ledger (
    id, part_id, location_id, quantity_delta, movement_type,
    work_order_id, actor_user_id, occurred_at, created_at
) VALUES (
    'e2000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000002',
    '60000000-0000-0000-0000-000000000011',
    -1,
    'CONSUMPTION',
    'c0000000-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000011',
    NOW() - INTERVAL '45 minutes',
    NOW() - INTERVAL '45 minutes'
) ON CONFLICT DO NOTHING;

-- WO-COMP-2: no_parts_required=true → satisfied without ledger entry
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id, state,
                        priority, description, no_parts_required, version, updated_at)
VALUES (
    'c0000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    'COMPLETED', 'LOW',
    'WO-150 completeness fixture: no parts required',
    true, 0,
    NOW() - INTERVAL '30 minutes'
) ON CONFLICT DO NOTHING;

-- WO-COMP-3: no ledger entry, no_parts_required=false → lowers completeness ratio
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id, state,
                        priority, description, no_parts_required, version, updated_at)
VALUES (
    'c0000000-0000-0000-0000-000000000003',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    'COMPLETED', 'LOW',
    'WO-150 completeness fixture: missing parts log (intentional gap)',
    false, 0,
    NOW() - INTERVAL '15 minutes'
) ON CONFLICT DO NOTHING;
