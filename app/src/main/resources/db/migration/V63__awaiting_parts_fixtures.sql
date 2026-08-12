-- WO-152 fixtures: parts at, above, and below reorder point; work orders in hold-eligible states.
-- Used by integration tests for guard predicates, threshold crossing, and hold emission.

-- Parts catalogue fixtures
INSERT INTO part (id, part_number, sku, name, description, unit_of_measure, unit, reorder_point, reorder_quantity, is_active)
VALUES
    -- Part below reorder point (triggers LOW_STOCK alert)
    ('a0000000-152f-7000-8000-000000000001', 'WO152-PART-LOW',   'WO152-PART-LOW',   'WO-152 Low-Stock Part',
     'Stock will be below reorder_point for alert testing', 'EACH', 'EACH', 10, 20, true),
    -- Part at reorder point (triggers LOW_STOCK alert — at threshold counts as at or below)
    ('a0000000-152f-7000-8000-000000000002', 'WO152-PART-AT',    'WO152-PART-AT',    'WO-152 At-Reorder Part',
     'Stock equals reorder_point for boundary test',        'EACH', 'EACH', 5,  10, true),
    -- Part above reorder point (no alert expected)
    ('a0000000-152f-7000-8000-000000000003', 'WO152-PART-OK',    'WO152-PART-OK',    'WO-152 Sufficient Stock Part',
     'Stock above reorder_point — no alert expected',       'EACH', 'EACH', 3,  6,  true),
    -- Part with stockout (quantityOnHand = 0, triggers STOCKOUT alert)
    ('a0000000-152f-7000-8000-000000000004', 'WO152-PART-ZERO',  'WO152-PART-ZERO',  'WO-152 Stockout Part',
     'Zero stock — triggers STOCKOUT alert',                'EACH', 'EACH', 5,  10, true),
    -- Part with reorder_point = 0 (must alert only on true stockout, not continuously)
    ('a0000000-152f-7000-8000-000000000005', 'WO152-PART-ROPO',  'WO152-PART-ROPO',  'WO-152 Zero-Reorder-Point Part',
     'reorder_point=0 — alert only on true stockout',       'EACH', 'EACH', 0,  5,  true);

-- Stock location for test vehicles
INSERT INTO stock_location (id, name, location_type, owner_reference_id, is_active)
VALUES
    ('b0000000-152f-7000-8000-000000000001', 'WO152 Test Van Alpha', 'VEHICLE', null, true),
    ('b0000000-152f-7000-8000-000000000002', 'WO152 Test Van Beta',  'VEHICLE', null, true);

-- Stock balances matching each part scenario
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, quantity_reserved, version)
VALUES
    -- Below reorder point (5 on hand, reorder_point=10)
    ('c0000000-152f-7000-8000-000000000001',
     'a0000000-152f-7000-8000-000000000001',
     'b0000000-152f-7000-8000-000000000001', 5, 0, 0),
    -- At reorder point (5 on hand, reorder_point=5)
    ('c0000000-152f-7000-8000-000000000002',
     'a0000000-152f-7000-8000-000000000002',
     'b0000000-152f-7000-8000-000000000001', 5, 0, 0),
    -- Above reorder point (10 on hand, reorder_point=3)
    ('c0000000-152f-7000-8000-000000000003',
     'a0000000-152f-7000-8000-000000000003',
     'b0000000-152f-7000-8000-000000000001', 10, 0, 0),
    -- Stockout (0 on hand, reorder_point=5)
    ('c0000000-152f-7000-8000-000000000004',
     'a0000000-152f-7000-8000-000000000004',
     'b0000000-152f-7000-8000-000000000001', 0, 0, 0),
    -- Zero reorder_point, non-zero stock (must NOT alert)
    ('c0000000-152f-7000-8000-000000000005',
     'a0000000-152f-7000-8000-000000000005',
     'b0000000-152f-7000-8000-000000000001', 3, 0, 0);
