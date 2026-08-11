-- seed-core.sql
-- Idempotent reference-data seed for the shared test suite.
--
-- Execution model: INSERT ... ON CONFLICT DO NOTHING against natural keys.
-- Re-running this script against the same database is a no-op; it never
-- produces duplicate rows even after a partial failure.
--
-- Coverage:
--   1. SLA policy rows for all four priority tiers (active — effective_to NULL).
--   2. Parts catalogue reference set (10 representative parts).
--   3. One customer with two sites and three assets (for cross-suite reuse).
--
-- All identifiers are fixed UUIDs in the ff000000-* range to avoid colliding
-- with V100 fixture UUIDs (aa/00/10/20/30/40/50/60/70-range).
--
-- All contact data uses IANA-reserved example.com / example.org domains and
-- NANP reserved +15555550xxx phone numbers. No real personal data is present.
--
-- Anonymisation assertion: see FixtureHygieneTest.java.

-- =============================================================================
-- SLA policies: one row per priority, each effective from 2020-01-01 and open
-- (effective_to NULL means currently active).
-- =============================================================================
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction, effective_from)
VALUES
    ('ff000000-0000-0000-0000-000000000001', 'CRITICAL',   60,   240, 0.75, '2020-01-01T00:00:00Z'),
    ('ff000000-0000-0000-0000-000000000002', 'HIGH',        240,  480, 0.75, '2020-01-01T00:00:00Z'),
    ('ff000000-0000-0000-0000-000000000003', 'MEDIUM',      480, 1440, 0.75, '2020-01-01T00:00:00Z'),
    ('ff000000-0000-0000-0000-000000000004', 'LOW',        1440, 4320, 0.75, '2020-01-01T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- Parts catalogue: 10 representative active parts.
-- IDs in ff000000-...-000000000011 .. 00000000001a range.
-- =============================================================================
INSERT INTO part (id, sku, name, unit, part_number, description, unit_of_measure, reorder_point, reorder_quantity, is_active)
VALUES
    ('ff000000-0000-0000-0000-000000000011', 'SEED-SKU-001', 'Refrigerant R-410A',         'CYLINDER', 'SEED-PN-001', 'Refrigerant cylinder 25lb', 'CYLINDER', 2, 5, true),
    ('ff000000-0000-0000-0000-000000000012', 'SEED-SKU-002', 'Air Filter 20x20x1',         'EACH',     'SEED-PN-002', 'Standard HVAC air filter',  'EACH',     10, 20, true),
    ('ff000000-0000-0000-0000-000000000013', 'SEED-SKU-003', 'Capacitor 45/5 MFD',         'EACH',     'SEED-PN-003', 'Dual run capacitor',         'EACH',     5, 10, true),
    ('ff000000-0000-0000-0000-000000000014', 'SEED-SKU-004', 'Contactor 24V',              'EACH',     'SEED-PN-004', 'AC contactor 24V coil',      'EACH',     3, 6,  true),
    ('ff000000-0000-0000-0000-000000000015', 'SEED-SKU-005', 'Thermostat Digital',         'EACH',     'SEED-PN-005', 'Programmable thermostat',    'EACH',     2, 4,  true),
    ('ff000000-0000-0000-0000-000000000016', 'SEED-SKU-006', 'Motor 1/3 HP',               'EACH',     'SEED-PN-006', 'Condenser fan motor',        'EACH',     1, 2,  true),
    ('ff000000-0000-0000-0000-000000000017', 'SEED-SKU-007', 'Blower Wheel 10x6',          'EACH',     'SEED-PN-007', 'Squirrel cage blower wheel', 'EACH',     1, 2,  true),
    ('ff000000-0000-0000-0000-000000000018', 'SEED-SKU-008', 'Expansion Valve TXV',        'EACH',     'SEED-PN-008', 'Thermal expansion valve',    'EACH',     2, 4,  true),
    ('ff000000-0000-0000-0000-000000000019', 'SEED-SKU-009', 'Circuit Breaker 20A',        'EACH',     'SEED-PN-009', '20A double-pole breaker',    'EACH',     3, 6,  true),
    ('ff000000-0000-0000-0000-000000000020', 'SEED-SKU-010', 'Dehumidifier Filter (inactive)', 'EACH', 'SEED-PN-010', 'Inactive seed part',        'EACH',     0, 0,  false)
ON CONFLICT (id) DO NOTHING;

-- =============================================================================
-- Reference customer: one customer with two sites and three assets.
-- Customer ID: ff000000-...-000000000031
-- Site IDs:    ff000000-...-000000000032 (Alpha), 000000000033 (Beta)
-- Asset IDs:   ff000000-...-000000000034, 035, 036
-- =============================================================================
INSERT INTO customer (id, name, contact_email, contact_phone, billing_address, is_active, version)
VALUES
    ('ff000000-0000-0000-0000-000000000031',
     'Seed Corp Alpha',
     'billing@example.com',
     '+15555550200',
     '200 Seed Street, Springfield, EX 00002',
     true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO site (id, customer_id, name, address, latitude, longitude, is_active, version)
VALUES
    ('ff000000-0000-0000-0000-000000000032',
     'ff000000-0000-0000-0000-000000000031',
     'Seed Site Alpha',
     '200 Seed Street, Springfield, EX 00002',
     34.0001, -118.0001,
     true, 0),
    ('ff000000-0000-0000-0000-000000000033',
     'ff000000-0000-0000-0000-000000000031',
     'Seed Site Beta',
     '201 Seed Avenue, Springfield, EX 00002',
     34.0052, -118.0052,
     true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO asset (id, site_id, name, asset_type, serial_no, version)
VALUES
    ('ff000000-0000-0000-0000-000000000034',
     'ff000000-0000-0000-0000-000000000032',
     'HVAC Unit Seed-Alpha-1', 'HVAC', 'SN-HVAC-SEED-001', 0),
    ('ff000000-0000-0000-0000-000000000035',
     'ff000000-0000-0000-0000-000000000032',
     'Boiler Seed-Alpha-1', 'BOILER', 'SN-BOIL-SEED-001', 0),
    ('ff000000-0000-0000-0000-000000000036',
     'ff000000-0000-0000-0000-000000000033',
     'Chiller Seed-Beta-1', 'CHILLER', 'SN-CHLL-SEED-001', 0)
ON CONFLICT (id) DO NOTHING;
