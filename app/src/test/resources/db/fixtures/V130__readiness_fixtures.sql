-- =============================================================================
-- V130: Readiness report test fixtures (WO-122)
--
-- Business date: 2026-08-12 (aligned with CertificationExpirySweepIT fixed clock)
-- warning_window_days = 30 (default)
--
-- Scenario           | Employee | Completeness status (with GAS_SAFE + employee_no requirements)
-- -------------------|----------|---------------------------------------------------------------
-- TECH-R1 (complete) | EMP-R01  | employee_no: set, GAS_SAFE: current (expires 2099-12-31)
-- TECH-R2 (missing cert) | EMP-R02 | employee_no: set, GAS_SAFE: no cert at all
-- TECH-R3 (expired cert) | EMP-R03 | employee_no: set, GAS_SAFE: expired 2020-01-01
-- TECH-R4 (missing field)| EMP-R04 | employee_no: NULL, GAS_SAFE: current
-- TECH-R5 (expiring soon)| EMP-R05 | employee_no: set, GAS_SAFE: expires 2026-09-05 (24 days away)
--
-- Expected readiness (default GAS_SAFE + employee_no requirements):
--   complete = TECH-R1 + TECH-R5 (expiring soon is NOT incomplete by gate definition) = 2
--   (TECH-R4 missing employee_no = incomplete)
--   Wait — TECH-R4 missing employee_no counts as incomplete.
--   complete = TECH-R1 only if GAS_SAFE+employee_no both required and TECH-R5 is complete
--   Actually: TECH-R5 has current GAS_SAFE (not expired) and has employee_no — so COMPLETE (expiring soon != incomplete)
--   => complete = TECH-R1 (1) + TECH-R5 (1) = 2, total = 5, readiness = 40.00%
--   blockingTechnicianCount = 3 (R2 missing cert, R3 expired cert, R4 missing field)
--   gateMet = false
--
-- Documented expected readiness percentage: 40.00% (2 of 5 active technicians complete)
-- =============================================================================

-- App users for readiness test technicians
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000021', 'ready-tech1@test.com', '$2a$10$placeholder.hash.r1.......', 'Readiness Tech 1 (complete)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000022', 'ready-tech2@test.com', '$2a$10$placeholder.hash.r2.......', 'Readiness Tech 2 (missing cert)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000023', 'ready-tech3@test.com', '$2a$10$placeholder.hash.r3.......', 'Readiness Tech 3 (expired cert)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000024', 'ready-tech4@test.com', '$2a$10$placeholder.hash.r4.......', 'Readiness Tech 4 (missing field)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000025', 'ready-tech5@test.com', '$2a$10$placeholder.hash.r5.......', 'Readiness Tech 5 (expiring soon)', true, 0)
ON CONFLICT (id) DO NOTHING;

-- Technicians — TECH-R4 intentionally has employee_no = NULL
INSERT INTO technician (id, user_id, employee_no, is_active, version) VALUES
    ('00000000-0000-0000-0000-000000000021', 'aaaaaaaa-0000-0000-0000-000000000021', 'EMP-R01', true, 0),
    ('00000000-0000-0000-0000-000000000022', 'aaaaaaaa-0000-0000-0000-000000000022', 'EMP-R02', true, 0),
    ('00000000-0000-0000-0000-000000000023', 'aaaaaaaa-0000-0000-0000-000000000023', 'EMP-R03', true, 0),
    ('00000000-0000-0000-0000-000000000024', 'aaaaaaaa-0000-0000-0000-000000000024', NULL,      true, 0),  -- missing employee_no
    ('00000000-0000-0000-0000-000000000025', 'aaaaaaaa-0000-0000-0000-000000000025', 'EMP-R05', true, 0)
ON CONFLICT (id) DO NOTHING;

-- TECH-R1: current GAS_SAFE (expires far in the future)
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT
    'dd000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000021',
    ct.id,
    'GS-READY-R1',
    '2024-01-01',
    '2099-12-31',
    'Gas Safe Register',
    true,
    0
FROM certification_type ct WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- TECH-R2: no GAS_SAFE cert at all — nothing to insert

-- TECH-R3: expired GAS_SAFE (2020-01-01)
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT
    'dd000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000023',
    ct.id,
    'GS-EXPIRED-R3',
    '2019-01-01',
    '2020-01-01',
    'Gas Safe Register',
    true,
    0
FROM certification_type ct WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- TECH-R4: current GAS_SAFE (employee_no is NULL → not complete)
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT
    'dd000000-0000-0000-0000-000000000004',
    '00000000-0000-0000-0000-000000000024',
    ct.id,
    'GS-READY-R4',
    '2024-01-01',
    '2099-12-31',
    'Gas Safe Register',
    true,
    0
FROM certification_type ct WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- TECH-R5: GAS_SAFE expiring in 24 days (2026-09-05) → expiring soon but CURRENT (complete)
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT
    'dd000000-0000-0000-0000-000000000005',
    '00000000-0000-0000-0000-000000000025',
    ct.id,
    'GS-EXPIRING-R5',
    '2024-01-01',
    '2026-09-05',
    'Gas Safe Register',
    true,
    0
FROM certification_type ct WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Readiness requirements for test — replace seeded requirements with a known set
-- We INSERT these with specific IDs so the definitionVersion is deterministic.
-- The seed requirements from V46 are kept; tests reference only GAS_SAFE + employee_no.
-- =============================================================================
