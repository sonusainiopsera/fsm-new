-- Test fixtures for WO-009 cross-role probe matrix
-- Two customer accounts, two sites (one per account), two work orders,
-- two technicians with separate assignments, one multi-account customer user.

-- Customer accounts
INSERT INTO sites (id, name, customer_account_id, address) VALUES
    ('aaaaaaaa-0001-0001-0001-000000000001', 'Acme HQ',    'cccccccc-0001-0001-0001-000000000001', '1 Acme Way'),
    ('aaaaaaaa-0002-0002-0002-000000000002', 'Beta Branch', 'cccccccc-0002-0002-0002-000000000002', '2 Beta Road');

-- Work orders: wo-001 belongs to Acme (account cc..01), wo-002 belongs to Beta (account cc..02)
INSERT INTO work_orders (id, title, state, site_id, assigned_technician_id, priority) VALUES
    ('bbbbbbbb-0001-0001-0001-000000000001', 'Fix HVAC at Acme HQ',    'ASSIGNED', 'aaaaaaaa-0001-0001-0001-000000000001', 'tech-001', 'HIGH'),
    ('bbbbbbbb-0002-0002-0002-000000000002', 'Replace pump at Beta',   'ASSIGNED', 'aaaaaaaa-0002-0002-0002-000000000002', 'tech-002', 'MEDIUM');

-- Assignment history
INSERT INTO assignments (id, work_order_id, technician_id, is_active) VALUES
    ('dddddddd-0001-0001-0001-000000000001', 'bbbbbbbb-0001-0001-0001-000000000001', 'tech-001', TRUE),
    ('dddddddd-0002-0002-0002-000000000002', 'bbbbbbbb-0002-0002-0002-000000000002', 'tech-002', TRUE);

-- Assets
INSERT INTO assets (id, name, site_id, asset_type) VALUES
    ('eeeeeeee-0001-0001-0001-000000000001', 'Acme HVAC Unit',    'aaaaaaaa-0001-0001-0001-000000000001', 'HVAC'),
    ('eeeeeeee-0002-0002-0002-000000000002', 'Beta Pump Station', 'aaaaaaaa-0002-0002-0002-000000000002', 'PUMP');
