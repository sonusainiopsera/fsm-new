-- seed-wo157.sql
-- Fixture for WO-157: log-work screen — labour time, parts consumption, vehicle stock.
--
-- UUID prefix convention:
--   00000000-0000-7157-0000-XXXXXXXXXXXX  WO-157 work orders
--   aa000000-0000-7157-0000-XXXXXXXXXXXX  WO-157 parts
--   bb000000-0000-7157-0000-XXXXXXXXXXXX  WO-157 stock locations
--   cc000000-0000-7157-0000-XXXXXXXXXXXX  WO-157 stock balances
--
-- Technician IDs match TestJwtFactory:
--   TECH_ONE_ID = cccccccc-0000-0000-0000-000000000001

-- ---- Parts -----------------------------------------------------------------
INSERT INTO part (id, part_number, name, description, unit_of_measure, reorder_point, reorder_quantity, active) VALUES
    ('aa000000-0000-7157-0000-000000000001', 'WO157-PART-A', 'Relay A',     'Single-pole relay', 'EA', 2, 5, TRUE),
    ('aa000000-0000-7157-0000-000000000002', 'WO157-PART-B', 'Capacitor B', 'Start capacitor',   'EA', 1, 3, TRUE)
ON CONFLICT (id) DO NOTHING;

-- ---- Vehicle stock location for Tech One ----------------------------------
INSERT INTO stock_location (id, name, location_type, technician_id) VALUES
    ('bb000000-0000-7157-0000-000000000001', 'WO157-Van-TechOne', 'VEHICLE',
     'cccccccc-0000-0000-0000-000000000001')
ON CONFLICT (id) DO NOTHING;

-- ---- Stock balances: PART-A has 3, PART-B has 1 --------------------------
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, quantity_reserved) VALUES
    ('cc000000-0000-7157-0000-000000000001',
     'aa000000-0000-7157-0000-000000000001',
     'bb000000-0000-7157-0000-000000000001', 3, 0),
    ('cc000000-0000-7157-0000-000000000002',
     'aa000000-0000-7157-0000-000000000002',
     'bb000000-0000-7157-0000-000000000001', 1, 0)
ON CONFLICT (part_id, location_id) DO NOTHING;

-- ---- Work order IN_PROGRESS assigned to Tech One -------------------------
-- Uses existing site and customer from seed-technician-day.sql (ON CONFLICT DO NOTHING ensures idempotency)
INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id, description,
     resolution_deadline, at_risk, version)
VALUES (
    '00000000-0000-7157-0000-000000000001',
    'WO157-INPROG', 'IN_PROGRESS', 'HIGH',
    'bbbbbbbb-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000001',
    'HVAC fan motor replacement.',
    '2026-09-15T17:00:00Z', FALSE, 2
) ON CONFLICT (id) DO NOTHING;
