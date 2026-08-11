-- V100__test_fixtures.sql
-- Test fixtures for cross-role probe matrix and scope enforcement tests.
--
-- Fixture topology:
--   Customer Account A (ACCT_A, id = 00000000-0000-0000-0000-000000000001):
--       sites site_a1, site_a2
--   Customer Account B (ACCT_B, id = 00000000-0000-0000-0000-000000000002):
--       sites site_b1
--   Technician 1 (TECH_1, id = 00000000-0000-0000-0000-000000000011):
--       assigned to wo_a1 (on site_a1), wo_b1 (on site_b1)
--   Technician 2 (TECH_2, id = 00000000-0000-0000-0000-000000000012):
--       assigned to wo_a2 (on site_a2)
--   Work order wo_unassigned: on site_a1 (ACCT_A), not assigned (state NEW)
--
-- All UUIDs are fixed so tests can reference them by constant.

-- =============================================================================
-- app_user rows (required for technician.user_id FK)
-- =============================================================================
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 'dispatcher@example.com', '$2a$10$placeholder.hash.dispatcher.....', 'Test Dispatcher', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'manager@example.com',    '$2a$10$placeholder.hash.manager........', 'Test Manager', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000011', 'tech1@example.com',      '$2a$10$placeholder.hash.tech1..........', 'Test Tech 1', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000012', 'tech2@example.com',      '$2a$10$placeholder.hash.tech2..........', 'Test Tech 2', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000021', 'customer@example.com',   '$2a$10$placeholder.hash.customer......', 'Test Customer', true, 0);

-- =============================================================================
-- customer rows (these are the row-scope boundaries — ACCT_A and ACCT_B)
-- =============================================================================
INSERT INTO customer (id, name, is_active, version) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Customer Account A', true, 0),
    ('00000000-0000-0000-0000-000000000002', 'Customer Account B', true, 0);

-- =============================================================================
-- site rows
-- =============================================================================
INSERT INTO site (id, customer_id, name, address, version) VALUES
    ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'Site A1', '1 Alpha Street', 0),
    ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'Site A2', '2 Alpha Avenue', 0),
    ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002', 'Site B1', '1 Beta Boulevard', 0);

-- =============================================================================
-- technician rows
-- =============================================================================
INSERT INTO technician (id, user_id, employee_no, is_active, version) VALUES
    ('00000000-0000-0000-0000-000000000011', 'aaaaaaaa-0000-0000-0000-000000000011', 'EMP-001', true, 0),
    ('00000000-0000-0000-0000-000000000012', 'aaaaaaaa-0000-0000-0000-000000000012', 'EMP-002', true, 0);

-- =============================================================================
-- asset rows
-- =============================================================================
INSERT INTO asset (id, site_id, name, asset_type, version) VALUES
    ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'HVAC Unit A1', 'HVAC', 0),
    ('20000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 'Boiler A2', 'BOILER', 0),
    ('20000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', 'Chiller B1', 'CHILLER', 0);

-- =============================================================================
-- work_order rows
-- wo_a1: site_a1 (ACCT_A), assigned to TECH_1
-- wo_a2: site_a2 (ACCT_A), assigned to TECH_2
-- wo_b1: site_b1 (ACCT_B), assigned to TECH_1 (cross-account)
-- wo_unassigned: site_a1 (ACCT_A), no technician (state NEW)
-- =============================================================================
INSERT INTO work_order (id, site_id, customer_id, asset_id, assigned_technician_id, state, priority, description, version) VALUES
    ('30000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'ASSIGNED', 'HIGH', 'HVAC repair at site A1 — assigned to Tech 1', 0),
    ('30000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000001',
     '20000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000012',
     'IN_PROGRESS', 'MEDIUM', 'Boiler service at site A2 — assigned to Tech 2', 0),
    ('30000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000003',
     '00000000-0000-0000-0000-000000000002',
     '20000000-0000-0000-0000-000000000003',
     '00000000-0000-0000-0000-000000000011',
     'ASSIGNED', 'CRITICAL', 'Chiller repair at site B1 — assigned to Tech 1', 0),
    ('30000000-0000-0000-0000-000000000004',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     NULL,
     NULL,
     'NEW', 'LOW', 'Routine inspection at site A1 — unassigned', 0);

-- =============================================================================
-- assignment rows (current assignments only)
-- =============================================================================
INSERT INTO assignment (id, work_order_id, technician_id, is_current, version) VALUES
    ('40000000-0000-0000-0000-000000000001',
     '30000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011', true, 0),
    ('40000000-0000-0000-0000-000000000002',
     '30000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000012', true, 0),
    ('40000000-0000-0000-0000-000000000003',
     '30000000-0000-0000-0000-000000000003',
     '00000000-0000-0000-0000-000000000011', true, 0);
