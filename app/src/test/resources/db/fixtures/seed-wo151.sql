-- seed-wo151.sql
-- Fixture for WO-151: parts availability scenarios
-- Covers FULLY_STOCKED, PARTIALLY_STOCKED, COLLECTABLE, UNAVAILABLE.

-- ── Parts ────────────────────────────────────────────────────────────────────
INSERT INTO part (id, part_number, name) VALUES
    ('a1000000-0000-7001-8000-000000000001', 'WO151-PART-A', 'Valve Assembly A'),
    ('a1000000-0000-7001-8000-000000000002', 'WO151-PART-B', 'Pump Seal B'),
    ('a1000000-0000-7001-8000-000000000003', 'WO151-PART-C', 'Pressure Gauge C'),
    ('a1000000-0000-7001-8000-000000000004', 'WO151-PART-D', 'Control Board D')
ON CONFLICT (id) DO NOTHING;

-- ── Stock Locations (vehicles and warehouses for dispatch test) ───────────────
INSERT INTO stock_location (id, name, location_type) VALUES
    ('b1000000-0000-7001-8000-000000000001', 'WO151-Warehouse-Alpha', 'WAREHOUSE'),
    ('b1000000-0000-7001-8000-000000000002', 'WO151-Warehouse-Beta',  'WAREHOUSE')
ON CONFLICT (id) DO NOTHING;

-- Vehicle locations linked to technicians
-- (Assumes technicians from V13 seed; using dedicated test technician IDs here)
INSERT INTO stock_location (id, name, location_type, technician_id) VALUES
    ('b1000000-0000-7001-8000-000000000011', 'WO151-Van-FullyStocked',    'VEHICLE', 'ffffffff-0000-7005-8000-000000000002'),
    ('b1000000-0000-7001-8000-000000000012', 'WO151-Van-Collectable',     'VEHICLE', 'ffffffff-0000-7005-8000-000000000003'),
    ('b1000000-0000-7001-8000-000000000013', 'WO151-Van-PartiallyStocked','VEHICLE', 'ffffffff-0000-7005-8000-000000000004'),
    ('b1000000-0000-7001-8000-000000000014', 'WO151-Van-Unavailable',     'VEHICLE', 'ffffffff-0000-7005-8000-000000000005')
ON CONFLICT (id) DO NOTHING;

-- ── Stock Balances ────────────────────────────────────────────────────────────
-- Fully stocked van: has all 4 required parts
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, quantity_reserved) VALUES
    ('c1000001-0000-7001-8000-000000000001', 'a1000000-0000-7001-8000-000000000001', 'b1000000-0000-7001-8000-000000000011', 10, 0),
    ('c1000001-0000-7001-8000-000000000002', 'a1000000-0000-7001-8000-000000000002', 'b1000000-0000-7001-8000-000000000011', 5,  0),
    ('c1000001-0000-7001-8000-000000000003', 'a1000000-0000-7001-8000-000000000003', 'b1000000-0000-7001-8000-000000000011', 3,  0),
    ('c1000001-0000-7001-8000-000000000004', 'a1000000-0000-7001-8000-000000000004', 'b1000000-0000-7001-8000-000000000011', 2,  0)
ON CONFLICT (part_id, location_id) DO NOTHING;

-- Collectable van: has nothing, but warehouse-alpha has everything
-- (No stock_balance rows for this van — all parts at warehouse)
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, quantity_reserved) VALUES
    ('c1000002-0000-7001-8000-000000000001', 'a1000000-0000-7001-8000-000000000001', 'b1000000-0000-7001-8000-000000000001', 20, 0),
    ('c1000002-0000-7001-8000-000000000002', 'a1000000-0000-7001-8000-000000000002', 'b1000000-0000-7001-8000-000000000001', 10, 0),
    ('c1000002-0000-7001-8000-000000000003', 'a1000000-0000-7001-8000-000000000003', 'b1000000-0000-7001-8000-000000000001', 8,  0),
    ('c1000002-0000-7001-8000-000000000004', 'a1000000-0000-7001-8000-000000000004', 'b1000000-0000-7001-8000-000000000001', 5,  0)
ON CONFLICT (part_id, location_id) DO NOTHING;

-- Partially stocked van: has PART_A and PART_B but not PART_C or PART_D;
--   PART_C is at warehouse but PART_D is nowhere
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, quantity_reserved) VALUES
    ('c1000003-0000-7001-8000-000000000001', 'a1000000-0000-7001-8000-000000000001', 'b1000000-0000-7001-8000-000000000013', 10, 0),
    ('c1000003-0000-7001-8000-000000000002', 'a1000000-0000-7001-8000-000000000002', 'b1000000-0000-7001-8000-000000000013', 5,  0),
    ('c1000003-0000-7001-8000-000000000003', 'a1000000-0000-7001-8000-000000000003', 'b1000000-0000-7001-8000-000000000002', 8,  0)
ON CONFLICT (part_id, location_id) DO NOTHING;

-- Unavailable van: no parts anywhere for PART_D (already captured above — warehouse-alpha missing PART_D)
-- Nothing in warehouse-beta either — confirmed by absence of PART_D rows for warehouse-beta
