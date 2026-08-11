-- V100__test_fixtures.sql
-- Test fixtures for cross-role probe matrix and scope enforcement tests.
--
-- Fixture topology:
--   Customer Account A (ACCT_A): sites site_a1, site_a2
--   Customer Account B (ACCT_B): sites site_b1
--   Technician 1 (TECH_1): assigned to wo_a1 (on site_a1), wo_b1 (on site_b1)
--   Technician 2 (TECH_2): assigned to wo_a2 (on site_a2)
--   Customer User (CUST_USER): linked to both ACCT_A and ACCT_B
--   Work order wo_unassigned: on site_a1, not assigned to any technician
--
-- All UUIDs are fixed so tests can reference them by constant.

-- Customer Account IDs (these represent external account entities, stored as UUIDs)
-- ACCT_A = 00000000-0000-0000-0000-000000000001
-- ACCT_B = 00000000-0000-0000-0000-000000000002
-- TECH_1 = 00000000-0000-0000-0000-000000000011
-- TECH_2 = 00000000-0000-0000-0000-000000000012

-- Sites
INSERT INTO site (id, name, address, customer_account_id) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Site A1', '1 Alpha Street', '00000000-0000-0000-0000-000000000001'),
    ('10000000-0000-0000-0000-000000000002', 'Site A2', '2 Alpha Avenue', '00000000-0000-0000-0000-000000000001'),
    ('10000000-0000-0000-0000-000000000003', 'Site B1', '1 Beta Boulevard', '00000000-0000-0000-0000-000000000002');

-- Assets
INSERT INTO asset (id, site_id, name, asset_type) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'HVAC Unit A1', 'HVAC'),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'Boiler A2', 'BOILER'),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', 'Chiller B1', 'CHILLER');

-- Work Orders
-- wo_a1: on site_a1 (ACCT_A), assigned to TECH_1
-- wo_a2: on site_a2 (ACCT_A), assigned to TECH_2
-- wo_b1: on site_b1 (ACCT_B), assigned to TECH_1 (overlapping assignment)
-- wo_unassigned: on site_a1 (ACCT_A), not assigned (state OPEN)
INSERT INTO work_order (id, site_id, asset_id, assigned_technician_id, state, priority, description) VALUES
    ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011', 'ASSIGNED', 'HIGH', 'HVAC repair at site A1 — assigned to Tech 1'),
    ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000012', 'IN_PROGRESS', 'MEDIUM', 'Boiler service at site A2 — assigned to Tech 2'),
    ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003',
     '00000000-0000-0000-0000-000000000011', 'ASSIGNED', 'CRITICAL', 'Chiller repair at site B1 — assigned to Tech 1'),
    ('30000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', NULL,
     NULL, 'OPEN', 'LOW', 'Routine inspection at site A1 — unassigned');

-- Assignments (current)
INSERT INTO assignment (id, work_order_id, technician_id, is_current) VALUES
    ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011', true),
    ('40000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000012', true),
    ('40000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000011', true);

-- Stock Movements for Tech 1
INSERT INTO stock_movement (id, technician_id, work_order_id, item_id, quantity, movement_type) VALUES
    ('50000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011',
     '30000000-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', 2, 'CONSUMPTION');
