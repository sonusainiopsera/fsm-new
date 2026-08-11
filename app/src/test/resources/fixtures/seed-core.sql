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

-- ---- Customer ---------------------------------------------------------------
INSERT INTO customer (id, name, contact_email, phone, version)
VALUES
    ('00000000-0000-7012-8000-000000000001', 'Seed Customer',
     'ops@example.local', '555-020-0001', 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Sites (two sites for the seed customer) --------------------------------
INSERT INTO site (id, name, customer_id, version)
VALUES
    ('00000000-0000-7013-8000-000000000001', 'Seed Site Alpha',
     '00000000-0000-7012-8000-000000000001', 0),
    ('00000000-0000-7013-8000-000000000002', 'Seed Site Beta',
     '00000000-0000-7012-8000-000000000001', 0)
ON CONFLICT (id) DO NOTHING;

-- ---- Assets (three assets: two on Alpha, one on Beta) -----------------------
INSERT INTO asset (id, site_id, serial_number, model, manufacturer, version)
VALUES
    ('00000000-0000-7014-8000-000000000001',
     '00000000-0000-7013-8000-000000000001',
     'SEED-SN-001', 'HeatPump-X1', 'ThermoCo', 0),
    ('00000000-0000-7014-8000-000000000002',
     '00000000-0000-7013-8000-000000000001',
     'SEED-SN-002', 'AirUnit-Y2',  'AirTech',  0),
    ('00000000-0000-7014-8000-000000000003',
     '00000000-0000-7013-8000-000000000002',
     'SEED-SN-003', 'Boiler-Z3',   'HeatCore',  0)
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
