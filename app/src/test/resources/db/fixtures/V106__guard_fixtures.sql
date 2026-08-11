-- V106__guard_fixtures.sql
-- Test fixtures for business precondition guard tests (WO-125).
--
-- Fixture topology additions:
--
-- Technicians and certifications (certification IDs: c0000000-...):
--   TECH_1 (00000000-...-000000000011):
--     - HVAC_CERT: current (expires 2099-01-01)
--     - ELECTRICAL_CERT: expires today (should be treated as expired)
--   TECH_2 (00000000-...-000000000012):
--     - no certifications (will fail certification guard)
--   TECH_3 (00000000-...-000000000013): new technician for guard tests
--     - HVAC_CERT: current
--     - ELECTRICAL_CERT: current
--
-- Work orders for guard tests (IDs: 3a000000-...):
--   wo_guard_in_progress (3a000000-...-000000000001): IN_PROGRESS, assigned to TECH_1, has labour time
--   wo_guard_no_labour  (3a000000-...-000000000002): IN_PROGRESS, assigned to TECH_1, NO labour time
--   wo_guard_completed  (3a000000-...-000000000003): COMPLETED, assigned to TECH_1, with unreconciled parts
--   wo_guard_reconciled (3a000000-...-000000000004): COMPLETED, assigned to TECH_1, all parts reconciled
--   wo_guard_no_parts   (3a000000-...-000000000005): COMPLETED, assigned to TECH_1, no parts consumed
--   wo_guard_new_with_competency  (3a000000-...-000000000006): NEW, requires HVAC_CERT
--   wo_guard_new_no_competency    (3a000000-...-000000000007): NEW, no required competencies

-- =============================================================================
-- Additional app_user for TECH_3
-- =============================================================================
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000013', 'tech3@example.com',
     '$2a$10$placeholder.hash.tech3..........', 'Test Tech 3 (Guard)', true, 0);

-- =============================================================================
-- TECH_3 technician row
-- =============================================================================
INSERT INTO technician (id, user_id, employee_no, is_active, version) VALUES
    ('00000000-0000-0000-0000-000000000013', 'aaaaaaaa-0000-0000-0000-000000000013', 'EMP-003', true, 0);

-- =============================================================================
-- technician_certification rows
--
-- TECH_1 (EMP-001):
--   cert c0 = HVAC_CERT, current (far-future expiry)
--   cert c1 = ELECTRICAL_CERT, expires at exactly the epoch 2020-01-01 (treated as expired in guard tests)
-- TECH_3 (EMP-003):
--   cert c2 = HVAC_CERT, current (far-future expiry)
--   cert c3 = ELECTRICAL_CERT, current (far-future expiry)
-- =============================================================================
INSERT INTO technician_certification
    (id, technician_id, cert_type, cert_reference, issued_at, expires_at, is_revoked) VALUES
    -- TECH_1: HVAC_CERT (current)
    ('c0000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'HVAC_CERT', 'REF-HVAC-001',
     '2020-01-01T00:00:00Z',
     '2099-01-01T00:00:00Z',
     false),
    -- TECH_1: ELECTRICAL_CERT (expired — expires in the past)
    ('c0000000-0000-0000-0000-000000000002',
     '00000000-0000-0000-0000-000000000011',
     'ELECTRICAL_CERT', 'REF-ELEC-001',
     '2020-01-01T00:00:00Z',
     '2020-06-01T00:00:00Z',
     false),
    -- TECH_3: HVAC_CERT (current)
    ('c0000000-0000-0000-0000-000000000003',
     '00000000-0000-0000-0000-000000000013',
     'HVAC_CERT', 'REF-HVAC-003',
     '2023-01-01T00:00:00Z',
     '2099-01-01T00:00:00Z',
     false),
    -- TECH_3: ELECTRICAL_CERT (current)
    ('c0000000-0000-0000-0000-000000000004',
     '00000000-0000-0000-0000-000000000013',
     'ELECTRICAL_CERT', 'REF-ELEC-003',
     '2023-01-01T00:00:00Z',
     '2099-01-01T00:00:00Z',
     false);

-- =============================================================================
-- Work orders for guard tests
-- =============================================================================
INSERT INTO work_order
    (id, site_id, customer_id, assigned_technician_id, state, priority, description, version)
VALUES
    -- IN_PROGRESS with labour time (passes LabourTimeRecordedGuard)
    ('3a000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'IN_PROGRESS', 'MEDIUM', 'Guard test: in_progress with labour', 0),
    -- IN_PROGRESS with NO labour time (fails LabourTimeRecordedGuard)
    ('3a000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'IN_PROGRESS', 'MEDIUM', 'Guard test: in_progress no labour', 0),
    -- COMPLETED with unreconciled parts (fails PartsReconciledGuard)
    ('3a000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'COMPLETED', 'MEDIUM', 'Guard test: completed unreconciled parts', 0),
    -- COMPLETED with all parts reconciled (passes PartsReconciledGuard)
    ('3a000000-0000-0000-0000-000000000004',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'COMPLETED', 'MEDIUM', 'Guard test: completed reconciled parts', 0),
    -- COMPLETED with no parts at all (passes PartsReconciledGuard — zero consumption)
    ('3a000000-0000-0000-0000-000000000005',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'COMPLETED', 'MEDIUM', 'Guard test: completed no parts', 0),
    -- NEW requiring HVAC_CERT (TECH_1 has HVAC, TECH_2 has none)
    ('3a000000-0000-0000-0000-000000000006',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     NULL,
     'NEW', 'MEDIUM', 'Guard test: new with HVAC_CERT requirement', 0),
    -- NEW with no competency requirements (passes CertificationCurrencyGuard for any technician)
    ('3a000000-0000-0000-0000-000000000007',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     NULL,
     'NEW', 'MEDIUM', 'Guard test: new no competencies', 0);

-- =============================================================================
-- labour_time_record: only wo_guard_in_progress gets labour time
-- =============================================================================
INSERT INTO labour_time_record (id, work_order_id, technician_id, minutes, work_date) VALUES
    ('e0000000-0000-0000-0000-000000000001',
     '3a000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     120,
     '2024-01-15T09:00:00Z');

-- =============================================================================
-- work_order_part_consumption:
--   wo_guard_completed (3a..003): 1 unreconciled part
--   wo_guard_reconciled (3a..004): 1 reconciled part
--   wo_guard_no_parts (3a..005): nothing
-- =============================================================================
INSERT INTO work_order_part_consumption
    (id, work_order_id, part_id, quantity, is_reconciled) VALUES
    -- unreconciled (CLOSE should fail)
    ('f0000000-0000-0000-0000-000000000001',
     '3a000000-0000-0000-0000-000000000003',
     '50000000-0000-0000-0000-000000000001',
     2,
     false),
    -- reconciled (CLOSE should pass)
    ('f0000000-0000-0000-0000-000000000002',
     '3a000000-0000-0000-0000-000000000004',
     '50000000-0000-0000-0000-000000000001',
     1,
     true);

-- =============================================================================
-- work_order_competency: wo_guard_new_with_competency requires HVAC_CERT
-- =============================================================================
INSERT INTO work_order_competency (id, work_order_id, competency_code) VALUES
    ('d0000000-0000-0000-0000-000000000001',
     '3a000000-0000-0000-0000-000000000006',
     'HVAC_CERT');
