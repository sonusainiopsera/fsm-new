-- stock-discrepancy-seed.sql
-- Deliberately mismatched balance for reconciliation discrepancy test.
-- Ledger sum = +50 (one RECEIPT), but balance = 99 (wrong — set directly).
-- The reconciliation sweep must detect this and alert without auto-correcting.

INSERT INTO part (id, part_number, name, unit_of_measure, reorder_point, reorder_quantity, active)
VALUES ('ee000000-0000-0000-0001-000000000001', 'DISC-P1', 'Discrepancy Test Part', 'EACH', 0, 0, TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO stock_location (id, name, location_type)
VALUES ('ee000000-0000-0000-0002-000000000001', 'Discrepancy Warehouse', 'WAREHOUSE')
ON CONFLICT (id) DO NOTHING;

-- Balance intentionally set to 99 (should be 50 to match ledger)
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, quantity_reserved, version)
VALUES ('ee000000-0000-0000-0003-000000000001',
        'ee000000-0000-0000-0001-000000000001',
        'ee000000-0000-0000-0002-000000000001',
        99, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- Ledger records only 50 units received — net delta = 50
INSERT INTO stock_ledger (id, part_id, from_location_id, delta_quantity, resulting_quantity,
                          movement_type, actor_user_id, correlation_id, occurred_at,
                          location_id, quantity_change, created_at)
VALUES ('ee000000-0000-0000-0004-000000000001',
        'ee000000-0000-0000-0001-000000000001',
        'ee000000-0000-0000-0002-000000000001',
        50, 50, 'RECEIPT',
        'ee000000-0000-0000-0099-000000000001',
        'ee000000-0000-0000-0099-000000000001',
        NOW() - INTERVAL '1 hour',
        'ee000000-0000-0000-0002-000000000001', 50, NOW() - INTERVAL '1 hour')
ON CONFLICT (id) DO NOTHING;
