-- =============================================================================
-- V4: Seed reference data
-- Representative customers, sites, assets, technicians, certifications, parts
-- and stock rows for downstream story tests.
-- UUIDs use the aaaaaaaa-…/bbbbbbbb-… fixed-pattern convention so test code
-- can reference them by constant without a lookup.
-- =============================================================================

-- ── Customers ────────────────────────────────────────────────────────────────

INSERT INTO customer (id, name, contact_email, contact_phone) VALUES
    ('cccccccc-0001-0001-0001-000000000001', 'Acme Industries',  'ops@acme.example',  '+1-555-0101'),
    ('cccccccc-0002-0002-0002-000000000002', 'Beta Logistics',   'ops@beta.example',  '+1-555-0202');

-- ── Sites ─────────────────────────────────────────────────────────────────────

INSERT INTO site (id, name, customer_id, address) VALUES
    ('aaaaaaaa-0001-0001-0001-000000000001', 'Acme HQ',     'cccccccc-0001-0001-0001-000000000001', '1 Acme Way, Springfield'),
    ('aaaaaaaa-0002-0002-0002-000000000002', 'Beta Branch', 'cccccccc-0002-0002-0002-000000000002', '2 Beta Road, Shelbyville');

-- ── Assets ───────────────────────────────────────────────────────────────────

INSERT INTO asset (id, name, site_id, asset_type, serial_number) VALUES
    ('eeeeeeee-0001-0001-0001-000000000001', 'Acme HVAC Unit',    'aaaaaaaa-0001-0001-0001-000000000001', 'HVAC',  'HVAC-001'),
    ('eeeeeeee-0002-0002-0002-000000000002', 'Beta Pump Station', 'aaaaaaaa-0002-0002-0002-000000000002', 'PUMP',  'PUMP-001');

-- ── App users (no real passwords in seed — development placeholder only) ──────

INSERT INTO app_user (id, email, password_hash, full_name) VALUES
    ('10000000-0001-0001-0001-000000000001', 'tech1@fieldservice.example', '$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', 'Alice Technician'),
    ('10000000-0002-0002-0002-000000000002', 'tech2@fieldservice.example', '$2a$10$BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB', 'Bob Technician'),
    ('10000000-0003-0003-0003-000000000003', 'disp@fieldservice.example',  '$2a$10$CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC', 'Carol Dispatcher');

-- ── Technicians ───────────────────────────────────────────────────────────────

INSERT INTO technician (id, user_id, full_name, email, phone) VALUES
    ('20000000-0001-0001-0001-000000000001', '10000000-0001-0001-0001-000000000001', 'Alice Technician', 'tech1@fieldservice.example', '+1-555-1001'),
    ('20000000-0002-0002-0002-000000000002', '10000000-0002-0002-0002-000000000002', 'Bob Technician',   'tech2@fieldservice.example', '+1-555-1002');

-- ── Certifications (one current, one expired) ─────────────────────────────────

INSERT INTO technician_certification (id, technician_id, cert_type, issued_at, expires_at) VALUES
    ('30000000-0001-0001-0001-000000000001', '20000000-0001-0001-0001-000000000001', 'HVAC_LEVEL2',    '2023-01-01 00:00:00+00', '2027-01-01 00:00:00+00'),
    ('30000000-0002-0002-0002-000000000002', '20000000-0001-0001-0001-000000000001', 'ELECTRICAL_L1',  '2020-01-01 00:00:00+00', '2022-01-01 00:00:00+00'),  -- expired
    ('30000000-0003-0003-0003-000000000003', '20000000-0002-0002-0002-000000000002', 'PUMP_SYSTEMS',   '2024-01-01 00:00:00+00', '2028-01-01 00:00:00+00');

-- ── Work orders ───────────────────────────────────────────────────────────────

INSERT INTO work_order (id, title, state, site_id, assigned_technician_id, priority) VALUES
    ('bbbbbbbb-0001-0001-0001-000000000001', 'Fix HVAC at Acme HQ',  'ASSIGNED', 'aaaaaaaa-0001-0001-0001-000000000001', 'tech-001', 'HIGH'),
    ('bbbbbbbb-0002-0002-0002-000000000002', 'Replace pump at Beta', 'ASSIGNED', 'aaaaaaaa-0002-0002-0002-000000000002', 'tech-002', 'MEDIUM');

-- ── Assignments ───────────────────────────────────────────────────────────────

INSERT INTO assignment (id, work_order_id, technician_id, is_active) VALUES
    ('dddddddd-0001-0001-0001-000000000001', 'bbbbbbbb-0001-0001-0001-000000000001', 'tech-001', TRUE),
    ('dddddddd-0002-0002-0002-000000000002', 'bbbbbbbb-0002-0002-0002-000000000002', 'tech-002', TRUE);

-- ── Parts ─────────────────────────────────────────────────────────────────────

INSERT INTO part (id, part_number, description, unit_cost) VALUES
    ('40000000-0001-0001-0001-000000000001', 'FILTER-HVAC-01', 'HVAC air filter 20x20',         12.50),
    ('40000000-0002-0002-0002-000000000002', 'SEAL-PUMP-01',   'Pump shaft seal replacement',   45.00);

-- ── Stock locations ───────────────────────────────────────────────────────────

INSERT INTO stock_location (id, name, site_id) VALUES
    ('50000000-0001-0001-0001-000000000001', 'Acme Parts Cage', 'aaaaaaaa-0001-0001-0001-000000000001'),
    ('50000000-0002-0002-0002-000000000002', 'Beta Parts Room',  'aaaaaaaa-0002-0002-0002-000000000002');

-- ── Stock balances ────────────────────────────────────────────────────────────

INSERT INTO stock_balance (id, part_id, location_id, quantity_on_hand) VALUES
    ('60000000-0001-0001-0001-000000000001', '40000000-0001-0001-0001-000000000001', '50000000-0001-0001-0001-000000000001', 10),
    ('60000000-0002-0002-0002-000000000002', '40000000-0002-0002-0002-000000000002', '50000000-0002-0002-0002-000000000002', 5);
