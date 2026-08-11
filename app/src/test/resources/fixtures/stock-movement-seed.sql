-- Deterministic low-stock fixture for StockMovementIT.
-- Inserts exactly 1 unit of PART-LO at a vehicle location owned by TECH-SM-01.
-- Using fixed UUIDs for assertion-level tests.

-- Customer and site
INSERT INTO customer (id, name, version) VALUES
    ('cc000000-0000-0000-0000-000000000001', 'SMTestCorp', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO site (id, name, customer_id, version) VALUES
    ('cc000000-0000-0000-0000-000000000011', 'SMTestSite', 'cc000000-0000-0000-0000-000000000001', 0)
ON CONFLICT (id) DO NOTHING;

-- Technician user + technician
INSERT INTO app_user (id, email, password_hash, full_name, active, version) VALUES
    ('cc000000-0000-0000-0000-000000000021', 'smtech@sm.test', 'x', 'SM Tech', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, full_name, version) VALUES
    ('cc000000-0000-0000-0000-000000000031', 'cc000000-0000-0000-0000-000000000021', 'SM Tech', 0)
ON CONFLICT (id) DO NOTHING;

-- Dispatcher user (privileged role — no technician record needed)
INSERT INTO app_user (id, email, password_hash, full_name, active, version) VALUES
    ('cc000000-0000-0000-0000-000000000022', 'disp@sm.test', 'x', 'SM Dispatcher', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- Vehicle stock location owned by the technician
INSERT INTO stock_location (id, name, location_type, technician_id) VALUES
    ('cc000000-0000-0000-0000-000000000041', 'Van - SM Tech', 'VEHICLE',
     'cc000000-0000-0000-0000-000000000031')
ON CONFLICT (id) DO NOTHING;

-- Parts: one with adequate stock (QTY=10), one with single-unit low stock (QTY=1)
INSERT INTO part (id, part_number, name, active, reorder_point, reorder_quantity) VALUES
    ('cc000000-0000-0000-0000-000000000051', 'PART-OK',  'OK Stock Part',  TRUE, 0, 0),
    ('cc000000-0000-0000-0000-000000000052', 'PART-LO',  'Low Stock Part', TRUE, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- Stock balances (version column exists on stock_balance from V1)
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand, quantity_reserved, version) VALUES
    ('cc000000-0000-0000-0000-000000000061',
     'cc000000-0000-0000-0000-000000000051',
     'cc000000-0000-0000-0000-000000000041', 10, 0, 0),
    ('cc000000-0000-0000-0000-000000000062',
     'cc000000-0000-0000-0000-000000000052',
     'cc000000-0000-0000-0000-000000000041', 1, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- Work order assigned to the technician (state = IN_PROGRESS so parts can be consumed)
INSERT INTO work_order (id, reference, state, priority, site_id,
    assigned_technician_id, at_risk, cumulative_hold_minutes, version) VALUES
    ('cc000000-0000-0000-0000-000000000071', 'SM-WO-001', 'IN_PROGRESS', 'HIGH',
     'cc000000-0000-0000-0000-000000000011',
     'cc000000-0000-0000-0000-000000000031', FALSE, 0, 0)
ON CONFLICT (id) DO NOTHING;
