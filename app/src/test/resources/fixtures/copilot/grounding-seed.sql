-- V127__grounding_copilot_fixtures.sql
-- Fixtures for copilot grounding integration tests (WO-177).
--
-- All PII-shaped values are entirely synthetic:
--   - Customer: "Whitmore Industrial Ltd" / "Patricia Whitmore" — invented names
--   - Email: patricia.whitmore@example.com (IANA reserved example.com)
--   - Phone: +15555550300 (NANP reserved 555 range)
--   - Address: "42 Fixture Lane, Springfield, EX 00042" — fictional
--   - Postcode: "EX1 4BB" — fictional UK-format postcode
-- No real personal data is present. See FixtureHygieneTest for assertion.
--
-- IDs are in the cc000000-* range (distinct from aa/ff ranges used elsewhere).
--
-- Layout:
--   1. Customer with PII-shaped fields (for redaction assertion tests)
--   2. Site at that customer
--   3. Asset at that site
--   4. Work order with asset_id set, assigned to tech1 (OPEN — the "current" WO)
--   5. Two prior CLOSED work orders on the same asset (for grounding retriever tests)
--   6. Work order with NO asset_id (for NO_ASSET_IDENTITY test)

-- ─── Customer ──────────────────────────────────────────────────────────────────
INSERT INTO customer (id, name, legal_name, primary_contact_name, primary_contact_email,
                      primary_contact_phone, contact_email, contact_phone,
                      billing_address, is_active, version)
VALUES
    ('cc000000-0000-0000-0000-000000000001',
     'Whitmore Industrial Ltd',
     'Whitmore Industrial Limited',
     'Patricia Whitmore',
     'patricia.whitmore@example.com',
     '+15555550300',
     'patricia.whitmore@example.com',
     '+15555550300',
     '42 Fixture Lane, Springfield, EX 00042',
     true, 0)
ON CONFLICT (id) DO NOTHING;

-- ─── Site ──────────────────────────────────────────────────────────────────────
INSERT INTO site (id, customer_id, name, address, postcode, latitude, longitude, is_active, version)
VALUES
    ('cc000000-0000-0000-0000-000000000010',
     'cc000000-0000-0000-0000-000000000001',
     'Whitmore North Plant',
     '42 Fixture Lane, Springfield, EX 00042',
     'EX1 4BB',
     51.5074, -0.1278,
     true, 0)
ON CONFLICT (id) DO NOTHING;

-- ─── Asset ─────────────────────────────────────────────────────────────────────
INSERT INTO asset (id, site_id, name, asset_type, serial_no, model, category, manufacturer, version)
VALUES
    ('cc000000-0000-0000-0000-000000000020',
     'cc000000-0000-0000-0000-000000000010',
     'Boiler Unit CC-001', 'BOILER', 'SN-BOILER-CC-001', 'ThermoMax 2000', 'HVAC', 'ThermoTech', 0)
ON CONFLICT (id) DO NOTHING;

-- ─── Current work order (OPEN, assigned to tech1, has asset_id) ────────────────
INSERT INTO work_order (id, customer_id, site_id, asset_id, assigned_technician_id,
                        state, priority, title, fault_description, origin, version)
VALUES
    ('cc000000-0000-0000-0000-000000000030',
     'cc000000-0000-0000-0000-000000000001',
     'cc000000-0000-0000-0000-000000000010',
     'cc000000-0000-0000-0000-000000000020',
     '00000000-0000-0000-0000-000000000011',  -- TECH_1_ID
     'IN_PROGRESS', 'HIGH',
     'Boiler overheating',
     'Customer Patricia Whitmore reports boiler at 42 Fixture Lane overheating. Contact +15555550300.',
     'DISPATCHER', 0)
ON CONFLICT (id) DO NOTHING;

-- ─── Prior closed work order #1 (prior service history, newest) ────────────────
INSERT INTO work_order (id, customer_id, site_id, asset_id, assigned_technician_id,
                        state, priority, title, fault_description, description,
                        origin, version, updated_at)
VALUES
    ('cc000000-0000-0000-0000-000000000031',
     'cc000000-0000-0000-0000-000000000001',
     'cc000000-0000-0000-0000-000000000010',
     'cc000000-0000-0000-0000-000000000020',
     '00000000-0000-0000-0000-000000000011',  -- TECH_1_ID
     'CLOSED', 'MEDIUM',
     'Boiler pressure loss',
     'Low pressure fault on ThermoMax 2000 unit at site.',
     'Replaced pressure relief valve. Tested to 4 bar. Resolved.',
     'DISPATCHER', 0,
     now() - interval '30 days')
ON CONFLICT (id) DO NOTHING;

-- Parts consumed on prior WO #1
INSERT INTO work_order_part_consumption (id, work_order_id, part_id, quantity, is_reconciled)
VALUES
    ('cc000000-0000-0000-0000-000000000041',
     'cc000000-0000-0000-0000-000000000031',
     'ff000000-0000-0000-0000-000000000013', -- Capacitor 45/5 MFD
     1, true)
ON CONFLICT (id) DO NOTHING;

-- ─── Prior closed work order #2 (prior service history, older) ─────────────────
INSERT INTO work_order (id, customer_id, site_id, asset_id, assigned_technician_id,
                        state, priority, title, fault_description, description,
                        origin, version, updated_at)
VALUES
    ('cc000000-0000-0000-0000-000000000032',
     'cc000000-0000-0000-0000-000000000001',
     'cc000000-0000-0000-0000-000000000010',
     'cc000000-0000-0000-0000-000000000020',
     '00000000-0000-0000-0000-000000000012',  -- TECH_2_ID (different tech — tests cross-tech history)
     'CLOSED', 'LOW',
     'Annual boiler service',
     'Routine annual service on ThermoMax 2000.',
     'Full service completed. Cleaned heat exchanger, replaced filter, checked all safety cutoffs.',
     'DISPATCHER', 0,
     now() - interval '365 days')
ON CONFLICT (id) DO NOTHING;

-- ─── Work order with NO asset_id (for NO_ASSET_IDENTITY verdict test) ──────────
INSERT INTO work_order (id, customer_id, site_id, assigned_technician_id,
                        state, priority, title, fault_description, origin, version)
VALUES
    ('cc000000-0000-0000-0000-000000000033',
     'cc000000-0000-0000-0000-000000000001',
     'cc000000-0000-0000-0000-000000000010',
     '00000000-0000-0000-0000-000000000011',  -- TECH_1_ID
     'NEW', 'LOW',
     'Unlinked work order',
     'No asset linked to this work order.',
     'DISPATCHER', 0)
ON CONFLICT (id) DO NOTHING;

-- ─── Work order for TECH_2 only (access-control isolation test) ────────────────
INSERT INTO work_order (id, customer_id, site_id, asset_id, assigned_technician_id,
                        state, priority, title, fault_description, origin, version)
VALUES
    ('cc000000-0000-0000-0000-000000000034',
     'cc000000-0000-0000-0000-000000000001',
     'cc000000-0000-0000-0000-000000000010',
     'cc000000-0000-0000-0000-000000000020',
     '00000000-0000-0000-0000-000000000012',  -- TECH_2_ID only
     'IN_PROGRESS', 'MEDIUM',
     'Boiler gas pressure low',
     'Pressure reading 1.2 bar, should be 1.5.',
     'DISPATCHER', 0)
ON CONFLICT (id) DO NOTHING;
