-- V4__seed_reference_data.sql
-- Representative seed data providing customers, sites, assets, technicians with
-- valid and expired certifications, parts, and stock balances for downstream story tests.
-- Uses UUIDs in the FF..FF range to avoid collisions with test fixture data.

-- ---- App users ---------------------------------------------------------------
INSERT INTO app_user (id, email, password_hash, full_name, active) VALUES
    ('ffffffff-0000-7001-8000-000000000001', 'alice@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Alice Dispatcher', TRUE),
    ('ffffffff-0000-7001-8000-000000000002', 'bob.tech@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Bob Technician', TRUE),
    ('ffffffff-0000-7001-8000-000000000003', 'carol.tech@example.com',
     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Carol Technician', TRUE);

-- ---- Customers ---------------------------------------------------------------
INSERT INTO customer (id, name, contact_email, phone) VALUES
    ('ffffffff-0000-7002-8000-000000000001', 'Seed Corp',     'ops@seedcorp.example',  '+44-1234-567890'),
    ('ffffffff-0000-7002-8000-000000000002', 'Demo Holdings', 'fm@demo.example',        '+44-9876-543210');

-- ---- Sites -------------------------------------------------------------------
INSERT INTO site (id, name, customer_id, address_line1, city, postcode) VALUES
    ('ffffffff-0000-7003-8000-000000000001', 'Seed Corp HQ',    'ffffffff-0000-7002-8000-000000000001',
     '1 Seed Lane',  'London',     'EC1A 1BB'),
    ('ffffffff-0000-7003-8000-000000000002', 'Demo Main Site',  'ffffffff-0000-7002-8000-000000000002',
     '10 Demo Road', 'Manchester', 'M1 1AA');

-- ---- Assets ------------------------------------------------------------------
INSERT INTO asset (id, site_id, serial_number, model, manufacturer) VALUES
    ('ffffffff-0000-7004-8000-000000000001', 'ffffffff-0000-7003-8000-000000000001',
     'SN-001', 'HeatPump-3000', 'ThermoCo'),
    ('ffffffff-0000-7004-8000-000000000002', 'ffffffff-0000-7003-8000-000000000002',
     'SN-002', 'AirUnit-X200',  'AirTech');

-- ---- Technicians -------------------------------------------------------------
INSERT INTO technician (id, user_id, full_name, phone) VALUES
    ('ffffffff-0000-7005-8000-000000000001', 'ffffffff-0000-7001-8000-000000000002',
     'Bob Technician',   '+44-7700-000001'),
    ('ffffffff-0000-7005-8000-000000000002', 'ffffffff-0000-7001-8000-000000000003',
     'Carol Technician', '+44-7700-000002');

-- ---- Technician certifications (one valid, one expired) ----------------------
INSERT INTO technician_certification (id, technician_id, certification_code, issued_at, expires_at) VALUES
    ('ffffffff-0000-7006-8000-000000000001', 'ffffffff-0000-7005-8000-000000000001',
     'F-GAS-2019', '2022-01-01T00:00:00Z', '2027-01-01T00:00:00Z'),  -- valid
    ('ffffffff-0000-7006-8000-000000000002', 'ffffffff-0000-7005-8000-000000000001',
     'ELEC-LV',    '2020-06-01T00:00:00Z', '2023-06-01T00:00:00Z'),  -- expired
    ('ffffffff-0000-7006-8000-000000000003', 'ffffffff-0000-7005-8000-000000000002',
     'F-GAS-2019', '2023-03-15T00:00:00Z', '2028-03-15T00:00:00Z');  -- valid

-- ---- Parts catalogue ---------------------------------------------------------
INSERT INTO part (id, part_number, name) VALUES
    ('ffffffff-0000-7007-8000-000000000001', 'FLT-001', 'HEPA Air Filter'),
    ('ffffffff-0000-7007-8000-000000000002', 'BLT-500', 'Drive Belt 500mm'),
    ('ffffffff-0000-7007-8000-000000000003', 'REF-R32',  'R32 Refrigerant Cylinder');

-- ---- Stock locations ---------------------------------------------------------
INSERT INTO stock_location (id, name, site_id) VALUES
    ('ffffffff-0000-7008-8000-000000000001', 'Central Warehouse', NULL),
    ('ffffffff-0000-7008-8000-000000000002', 'Van Stock - Bob',   NULL);

-- ---- Stock balances ----------------------------------------------------------
INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand) VALUES
    ('ffffffff-0000-7009-8000-000000000001',
     'ffffffff-0000-7007-8000-000000000001', 'ffffffff-0000-7008-8000-000000000001', 50),
    ('ffffffff-0000-7009-8000-000000000002',
     'ffffffff-0000-7007-8000-000000000002', 'ffffffff-0000-7008-8000-000000000001', 25),
    ('ffffffff-0000-7009-8000-000000000003',
     'ffffffff-0000-7007-8000-000000000001', 'ffffffff-0000-7008-8000-000000000002', 5),
    ('ffffffff-0000-7009-8000-000000000004',
     'ffffffff-0000-7007-8000-000000000003', 'ffffffff-0000-7008-8000-000000000002', 2);
