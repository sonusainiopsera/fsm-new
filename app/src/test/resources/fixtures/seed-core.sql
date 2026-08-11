-- seed-core.sql
-- Idempotent reference fixture for integration tests.
-- Creates one customer with two sites and three assets, SLA policies per
-- priority tier, and a representative parts catalogue.
-- Uses ON CONFLICT DO NOTHING against natural/primary keys so re-execution is a no-op.
--
-- UUID prefix convention:
--   00000000-0000-7011-8000-XXXXXXXXXXXX  seed-core SLA policies (distinct from V2 migration rows)
--   00000000-0000-7012-8000-XXXXXXXXXXXX  seed-core customers
--   00000000-0000-7013-8000-XXXXXXXXXXXX  seed-core sites
--   00000000-0000-7014-8000-XXXXXXXXXXXX  seed-core assets
--   00000000-0000-7015-8000-XXXXXXXXXXXX  seed-core parts
--   00000000-0000-7016-8000-XXXXXXXXXXXX  seed-core stock locations
--
-- password_hash values are BCrypt cost-12 hashes of 'TestPassword123!'
-- Hash: $2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4o1TDH7SqC

-- ---- SLA policies (one per priority tier, effective 2025-01-01) -------------
-- These supplement the V2 migration placeholder rows with test-controlled rows
-- at the seed-core timestamp for deadline derivation.
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction, effective_from)
VALUES
    ('00000000-0000-7011-8000-000000000001', 'LOW',      240,  480, 0.80, '2025-01-01T00:00:00Z'),
    ('00000000-0000-7011-8000-000000000002', 'MEDIUM',   120,  240, 0.80, '2025-01-01T00:00:00Z'),
    ('00000000-0000-7011-8000-000000000003', 'HIGH',      60,  120, 0.80, '2025-01-01T00:00:00Z'),
    ('00000000-0000-7011-8000-000000000004', 'CRITICAL',  30,   60, 0.80, '2025-01-01T00:00:00Z')
ON CONFLICT (priority, effective_from) DO NOTHING;

-- Expired MEDIUM policy (superseded by the row above; effective_to in the past)
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction,
                        effective_from, effective_to, active)
VALUES (
    '00000000-0000-7011-8000-000000000011',
    'MEDIUM', 180, 360, 0.80,
    '2024-01-01T00:00:00Z',
    '2025-01-01T00:00:00Z',
    FALSE
) ON CONFLICT (priority, effective_from) DO NOTHING;

-- Future HIGH policy (not yet in effect; effective_from is far in the future)
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction,
                        effective_from, active)
VALUES (
    '00000000-0000-7011-8000-000000000012',
    'HIGH', 30, 90, 0.75,
    '2099-01-01T00:00:00Z',
    TRUE
) ON CONFLICT (priority, effective_from) DO NOTHING;

-- ---- Customers (3 active + 1 inactive) -------------------------------------
-- UUID prefix 7012 = seed customers
INSERT INTO customer (id, name, account_code, legal_name, active, contact_email, phone, version)
VALUES
    ('00000000-0000-7012-8000-000000000001', 'Acme Facilities Ltd',
     'ACM-001', 'Acme Facilities Ltd', TRUE, 'ops@acme.example', '555-020-0001', 0),
    ('00000000-0000-7012-8000-000000000002', 'Bluestone Property Group',
     'BSP-001', 'Bluestone Property Group', TRUE, 'fm@bluestone.example', '555-020-0002', 0),
    ('00000000-0000-7012-8000-000000000003', 'Crestwood Logistics',
     'CWL-001', 'Crestwood Logistics Ltd', TRUE, 'ops@crestwood.example', '555-020-0003', 0),
    ('00000000-0000-7012-8000-000000000004', 'Deactivated Corp',
     'DXC-001', 'Deactivated Corp', FALSE, 'admin@deactivated.example', '555-020-0004', 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Sites (8+ across customers, including one inactive) --------------------
-- UUID prefix 7013 = seed sites
-- Acme sites (4)
INSERT INTO site (id, name, site_code, display_name, customer_id, address_line1, city, postcode, active, version)
VALUES
    ('00000000-0000-7013-8000-000000000001', 'Acme HQ',
     'ACM-S01', 'Acme HQ', '00000000-0000-7012-8000-000000000001',
     '1 Industrial Way', 'Manchester', 'M1 1AA', TRUE, 0),
    ('00000000-0000-7013-8000-000000000002', 'Acme Warehouse North',
     'ACM-S02', 'Acme Warehouse North', '00000000-0000-7012-8000-000000000001',
     '45 North Road', 'Leeds', 'LS1 2BB', TRUE, 0),
    ('00000000-0000-7013-8000-000000000003', 'Acme Data Centre',
     'ACM-S03', 'Acme Data Centre', '00000000-0000-7012-8000-000000000001',
     '99 Server Lane', 'Sheffield', 'S1 3CC', TRUE, 0),
    -- Inactive site (child of active customer)
    ('00000000-0000-7013-8000-000000000004', 'Acme Closed Branch',
     'ACM-S04', 'Acme Closed Branch', '00000000-0000-7012-8000-000000000001',
     '7 Old Mill Road', 'Bradford', 'BD1 4DD', FALSE, 0)
ON CONFLICT (id) DO NOTHING;

-- Bluestone sites (2)
INSERT INTO site (id, name, site_code, display_name, customer_id, address_line1, city, postcode, active, version)
VALUES
    ('00000000-0000-7013-8000-000000000005', 'Bluestone Tower',
     'BSP-S01', 'Bluestone Tower', '00000000-0000-7012-8000-000000000002',
     '10 Canary Wharf', 'London', 'E14 5AB', TRUE, 0),
    ('00000000-0000-7013-8000-000000000006', 'Bluestone Annex',
     'BSP-S02', 'Bluestone Annex', '00000000-0000-7012-8000-000000000002',
     '22 Riverside Drive', 'London', 'SE1 7PQ', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- Crestwood sites (2)
INSERT INTO site (id, name, site_code, display_name, customer_id, address_line1, city, postcode, active, version)
VALUES
    ('00000000-0000-7013-8000-000000000007', 'Crestwood DC East',
     'CWL-S01', 'Crestwood DC East', '00000000-0000-7012-8000-000000000003',
     '5 Logistics Park', 'Coventry', 'CV1 2EF', TRUE, 0),
    ('00000000-0000-7013-8000-000000000008', 'Crestwood DC West',
     'CWL-S02', 'Crestwood DC West', '00000000-0000-7012-8000-000000000003',
     '30 Freight Road', 'Birmingham', 'B1 1GH', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Assets (20+ across sites including inactive) ---------------------------
-- UUID prefix 7014 = seed assets
-- Acme HQ assets (5)
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES
    ('00000000-0000-7014-8000-000000000001',
     '00000000-0000-7013-8000-000000000001',
     'ACM-A001', 'SEED-SN-001', 'HeatPump-X1', 'ThermoCo', 'HVAC', TRUE, 0),
    ('00000000-0000-7014-8000-000000000002',
     '00000000-0000-7013-8000-000000000001',
     'ACM-A002', 'SEED-SN-002', 'AirUnit-Y2', 'AirTech', 'HVAC', TRUE, 0),
    ('00000000-0000-7014-8000-000000000003',
     '00000000-0000-7013-8000-000000000001',
     'ACM-A003', 'SEED-SN-003', 'Chiller-Z3', 'CoolTech', 'HVAC', TRUE, 0),
    ('00000000-0000-7014-8000-000000000004',
     '00000000-0000-7013-8000-000000000001',
     'ACM-A004', 'SEED-SN-004', 'Generator-G1', 'PowerCo', 'ELECTRICAL', TRUE, 0),
    ('00000000-0000-7014-8000-000000000005',
     '00000000-0000-7013-8000-000000000001',
     'ACM-A005', 'SEED-SN-005', 'UPS-U1', 'PowerCo', 'ELECTRICAL', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- Acme Warehouse North assets (4)
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES
    ('00000000-0000-7014-8000-000000000006',
     '00000000-0000-7013-8000-000000000002',
     'ACM-A006', 'SEED-SN-006', 'Boiler-B1', 'HeatCore', 'HEATING', TRUE, 0),
    ('00000000-0000-7014-8000-000000000007',
     '00000000-0000-7013-8000-000000000002',
     'ACM-A007', 'SEED-SN-007', 'Pump-P1', 'FluidTech', 'PLUMBING', TRUE, 0),
    ('00000000-0000-7014-8000-000000000008',
     '00000000-0000-7013-8000-000000000002',
     'ACM-A008', 'SEED-SN-008', 'AHU-H1', 'AirTech', 'HVAC', TRUE, 0),
    -- Inactive asset on active site
    ('00000000-0000-7014-8000-000000000009',
     '00000000-0000-7013-8000-000000000002',
     'ACM-A009', 'SEED-SN-009', 'OldUnit-O1', 'Obsolete', 'HVAC', FALSE, 0)
ON CONFLICT (id) DO NOTHING;

-- Acme Data Centre assets (3)
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES
    ('00000000-0000-7014-8000-000000000010',
     '00000000-0000-7013-8000-000000000003',
     'ACM-A010', 'SEED-SN-010', 'CRAC-C1', 'DataCool', 'COOLING', TRUE, 0),
    ('00000000-0000-7014-8000-000000000011',
     '00000000-0000-7013-8000-000000000003',
     'ACM-A011', 'SEED-SN-011', 'PDU-P1', 'PowerDist', 'ELECTRICAL', TRUE, 0),
    ('00000000-0000-7014-8000-000000000012',
     '00000000-0000-7013-8000-000000000003',
     'ACM-A012', 'SEED-SN-012', 'CRAC-C2', 'DataCool', 'COOLING', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- Acme Closed Branch assets — deactivated because their site was deactivated
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES
    ('00000000-0000-7014-8000-000000000013',
     '00000000-0000-7013-8000-000000000004',
     'ACM-A013', 'SEED-SN-013', 'OldHVAC-H1', 'ThermoCo', 'HVAC', FALSE, 0),
    ('00000000-0000-7014-8000-000000000014',
     '00000000-0000-7013-8000-000000000004',
     'ACM-A014', 'SEED-SN-014', 'OldBoiler-B1', 'HeatCore', 'HEATING', FALSE, 0)
ON CONFLICT (id) DO NOTHING;

-- Bluestone Tower assets (3)
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES
    ('00000000-0000-7014-8000-000000000015',
     '00000000-0000-7013-8000-000000000005',
     'BSP-A001', 'SEED-SN-015', 'Lift-L1', 'ElevCo', 'LIFT', TRUE, 0),
    ('00000000-0000-7014-8000-000000000016',
     '00000000-0000-7013-8000-000000000005',
     'BSP-A002', 'SEED-SN-016', 'AHU-H2', 'AirTech', 'HVAC', TRUE, 0),
    ('00000000-0000-7014-8000-000000000017',
     '00000000-0000-7013-8000-000000000005',
     'BSP-A003', 'SEED-SN-017', 'Chiller-Z4', 'CoolTech', 'HVAC', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- Bluestone Annex assets (2)
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES
    ('00000000-0000-7014-8000-000000000018',
     '00000000-0000-7013-8000-000000000006',
     'BSP-A004', 'SEED-SN-018', 'HeatPump-X2', 'ThermoCo', 'HVAC', TRUE, 0),
    ('00000000-0000-7014-8000-000000000019',
     '00000000-0000-7013-8000-000000000006',
     'BSP-A005', 'SEED-SN-019', 'Boiler-B2', 'HeatCore', 'HEATING', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- Crestwood assets (3 across both DC sites)
INSERT INTO asset (id, site_id, asset_tag, serial_number, model, manufacturer, category, active, version)
VALUES
    ('00000000-0000-7014-8000-000000000020',
     '00000000-0000-7013-8000-000000000007',
     'CWL-A001', 'SEED-SN-020', 'DockDoor-D1', 'DoorTech', 'MECHANICAL', TRUE, 0),
    ('00000000-0000-7014-8000-000000000021',
     '00000000-0000-7013-8000-000000000007',
     'CWL-A002', 'SEED-SN-021', 'Conveyor-C1', 'ConveyorCo', 'MECHANICAL', TRUE, 0),
    ('00000000-0000-7014-8000-000000000022',
     '00000000-0000-7013-8000-000000000008',
     'CWL-A003', 'SEED-SN-022', 'Racking-R1', 'RackTech', 'MECHANICAL', TRUE, 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Parts catalogue --------------------------------------------------------
INSERT INTO part (id, part_number, name, unit_of_measure, reorder_point, reorder_quantity, active)
VALUES
    ('00000000-0000-7015-8000-000000000001',
     'SEED-FLT-001', 'HEPA Air Filter',           'EACH', 5, 10, TRUE),
    ('00000000-0000-7015-8000-000000000002',
     'SEED-BLT-500', 'Drive Belt 500mm',           'EACH', 3,  6, TRUE),
    ('00000000-0000-7015-8000-000000000003',
     'SEED-REF-R32',  'R32 Refrigerant Cylinder',  'UNIT', 2,  4, TRUE),
    ('00000000-0000-7015-8000-000000000004',
     'SEED-CAP-001', 'Start Capacitor 40uF',       'EACH', 5, 10, TRUE),
    ('00000000-0000-7015-8000-000000000005',
     'SEED-OIL-001', 'Compressor Oil 1L',          'LITRE',10, 20, TRUE)
ON CONFLICT (part_number) DO NOTHING;

-- ---- Stock locations --------------------------------------------------------
INSERT INTO stock_location (id, name, location_type, technician_id, site_id)
VALUES
    ('00000000-0000-7016-8000-000000000001',
     'Seed Central Warehouse', 'WAREHOUSE', NULL, NULL),
    ('00000000-0000-7016-8000-000000000002',
     'Seed Alpha Site Store',  'WAREHOUSE', NULL,
     '00000000-0000-7013-8000-000000000001')
ON CONFLICT (id) DO NOTHING;
