-- V117__backlog_workload_fixtures.sql
-- Test fixtures for WO-165 backlog and workload balance metrics.
--
-- Assets: uses site_a1 (10000000-0000-0000-0000-000000000001) and customer
--         (00000000-0000-0000-0000-000000000001) from V100.
--
-- Technicians: uses TECH_1 (00000000-0000-0000-0000-000000000011) from V100.
--              Adds TECH_2..TECH_5 as new technician rows.
--
-- Scenarios:
--   A) Evenly-loaded team: TECH_1/2/3 each with 600 minutes (10h) over last 7 days
--   B) Severely imbalanced team: TECH_4 with 1200min, TECH_5 with 60min (same window)
--   C) Two-technician team (uses only TECH_4 and TECH_5 in unit tests, not fixture-needed)
--   D) Work orders in every open state across all priorities
--   E) On-hold work orders with seeded hold reasons

-- -------------------------------------------------------------------------
-- Additional users and technicians (TECH_3..TECH_5; TECH_1 and TECH_2 from V100)
-- -------------------------------------------------------------------------
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version)
VALUES
    ('aaaaaaaa-0000-0000-0000-000000000013', 'tech3@example.com', '$2a$10$placeholder.hash.tech3..........', 'Test Tech 3', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000014', 'tech4@example.com', '$2a$10$placeholder.hash.tech4..........', 'Test Tech 4', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000015', 'tech5@example.com', '$2a$10$placeholder.hash.tech5..........', 'Test Tech 5', true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, employee_no, is_active, version)
VALUES
    ('00000000-0000-0000-0000-000000000013', 'aaaaaaaa-0000-0000-0000-000000000013', 'EMP-003', true, 0),
    ('00000000-0000-0000-0000-000000000014', 'aaaaaaaa-0000-0000-0000-000000000014', 'EMP-004', true, 0),
    ('00000000-0000-0000-0000-000000000015', 'aaaaaaaa-0000-0000-0000-000000000015', 'EMP-005', true, 0)
ON CONFLICT (id) DO NOTHING;

-- -------------------------------------------------------------------------
-- D) Open work orders in every state and priority
-- -------------------------------------------------------------------------
INSERT INTO work_order (id, site_id, customer_id, state, priority, description, no_parts_required, version, created_at, updated_at)
VALUES
    -- NEW
    ('90000000-0000-0000-0001-000000000001','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','NEW','LOW','Backlog NEW LOW',false,0,now()-INTERVAL'2 days',now()-INTERVAL'2 days'),
    ('90000000-0000-0000-0001-000000000002','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','NEW','CRITICAL','Backlog NEW CRITICAL',false,0,now()-INTERVAL'1 day',now()-INTERVAL'1 day'),
    -- ASSIGNED
    ('90000000-0000-0000-0001-000000000003','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','ASSIGNED','MEDIUM','Backlog ASSIGNED',false,0,now()-INTERVAL'3 days',now()-INTERVAL'3 days'),
    -- EN_ROUTE
    ('90000000-0000-0000-0001-000000000004','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','EN_ROUTE','HIGH','Backlog EN_ROUTE',false,0,now()-INTERVAL'1 day',now()-INTERVAL'1 day'),
    -- IN_PROGRESS
    ('90000000-0000-0000-0001-000000000005','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','IN_PROGRESS','MEDIUM','Backlog IN_PROGRESS',false,0,now()-INTERVAL'4 hours',now()-INTERVAL'4 hours'),
    -- ON_HOLD with each seeded hold reason
    ('90000000-0000-0000-0001-000000000006','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','ON_HOLD','MEDIUM','Backlog ON_HOLD AWAITING_PARTS',false,0,now()-INTERVAL'2 days',now()-INTERVAL'2 days'),
    ('90000000-0000-0000-0001-000000000007','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','ON_HOLD','LOW','Backlog ON_HOLD CUSTOMER_UNAVAILABLE',false,0,now()-INTERVAL'1 day',now()-INTERVAL'1 day')
ON CONFLICT (id) DO NOTHING;

-- -------------------------------------------------------------------------
-- E) Active hold records for ON_HOLD work orders
-- -------------------------------------------------------------------------
INSERT INTO work_order_hold (id, work_order_id, reason_code, note, started_at)
VALUES
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000006', 'AWAITING_PARTS',       'Waiting for compressor', now()-INTERVAL'2 days'),
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000007', 'CUSTOMER_UNAVAILABLE', 'Customer on holiday',    now()-INTERVAL'1 day')
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------
-- A) Current assignments for active technicians
-- -------------------------------------------------------------------------
INSERT INTO assignment (id, work_order_id, technician_id, is_current, version)
VALUES
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000003', '00000000-0000-0000-0000-000000000011', true, 0),
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000004', '00000000-0000-0000-0000-000000000012', true, 0),
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000005', '00000000-0000-0000-0000-000000000013', true, 0),
    -- Imbalanced team
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000006', '00000000-0000-0000-0000-000000000014', true, 0),
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000007', '00000000-0000-0000-0000-000000000015', true, 0)
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------
-- Labour time records for workload balance testing
-- -------------------------------------------------------------------------
-- Evenly-loaded team (TECH 1/2/3): 600 minutes (10h) each
INSERT INTO labour_time_record (id, work_order_id, technician_id, minutes, work_date, created_at)
VALUES
    -- TECH_1: 600 min total
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000003', '00000000-0000-0000-0000-000000000011', 300, now()-INTERVAL'2 days', now()),
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000003', '00000000-0000-0000-0000-000000000011', 300, now()-INTERVAL'1 day',  now()),
    -- TECH_2: 600 min total
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000004', '00000000-0000-0000-0000-000000000012', 600, now()-INTERVAL'1 day',  now()),
    -- TECH_3: 600 min total
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000005', '00000000-0000-0000-0000-000000000013', 600, now()-INTERVAL'3 days', now()),
    -- Imbalanced: TECH_4 = 1200 min, TECH_5 = 60 min
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000006', '00000000-0000-0000-0000-000000000014', 1200, now()-INTERVAL'1 day', now()),
    (gen_random_uuid(), '90000000-0000-0000-0001-000000000007', '00000000-0000-0000-0000-000000000015', 60,   now()-INTERVAL'1 day', now())
ON CONFLICT DO NOTHING;
