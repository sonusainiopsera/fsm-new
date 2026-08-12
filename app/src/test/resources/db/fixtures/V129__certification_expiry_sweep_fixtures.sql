-- =============================================================================
-- V129: Certification expiry sweep fixtures (WO-120)
--
-- Provides certifications at boundary distances from the business date
-- used in CertificationExpirySweepIT (fixed clock: 2026-08-12).
--
-- Cohort mapping (warning=30, urgent=7):
--   + 31 days → 2026-09-12 → no cohort
--   + 30 days → 2026-09-11 → WARNING
--   +  8 days → 2026-08-20 → WARNING (between 7 and 30)
--   +  7 days → 2026-08-19 → URGENT
--   +  1 day  → 2026-08-13 → URGENT
--   +  0 days → 2026-08-12 → EXPIRED (expires today)
--   - 1 day   → 2026-08-11 → EXPIRED
--
-- Also includes a re-issued certification to verify re-issue reset (AC-10).
--
-- All synthetic data — no real personal information.
--
-- Requires: V100 technicians (TECHs 0011-0015), V39 certification_type
-- =============================================================================

-- Additional technicians for sweep tests (TECHs 0016-0020)
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000016', 'sweep-tech6@test.com',  '$2a$10$placeholder.hash.6........', 'Sweep Tech 6 (31d)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000017', 'sweep-tech7@test.com',  '$2a$10$placeholder.hash.7........', 'Sweep Tech 7 (30d)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000018', 'sweep-tech8@test.com',  '$2a$10$placeholder.hash.8........', 'Sweep Tech 8 (8d)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000019', 'sweep-tech9@test.com',  '$2a$10$placeholder.hash.9........', 'Sweep Tech 9 (7d)', true, 0),
    ('aaaaaaaa-0000-0000-0000-00000000001a', 'sweep-tech10@test.com', '$2a$10$placeholder.hash.10.......', 'Sweep Tech 10 (1d)', true, 0),
    ('aaaaaaaa-0000-0000-0000-00000000001b', 'sweep-tech11@test.com', '$2a$10$placeholder.hash.11.......', 'Sweep Tech 11 (0d)', true, 0),
    ('aaaaaaaa-0000-0000-0000-00000000001c', 'sweep-tech12@test.com', '$2a$10$placeholder.hash.12.......', 'Sweep Tech 12 (-1d)', true, 0),
    ('aaaaaaaa-0000-0000-0000-00000000001d', 'sweep-tech13@test.com', '$2a$10$placeholder.hash.13.......', 'Sweep Tech 13 (re-issue)', true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, employee_no, is_active, version) VALUES
    ('00000000-0000-0000-0000-000000000016', 'aaaaaaaa-0000-0000-0000-000000000016', 'EMP-S06', true, 0),
    ('00000000-0000-0000-0000-000000000017', 'aaaaaaaa-0000-0000-0000-000000000017', 'EMP-S07', true, 0),
    ('00000000-0000-0000-0000-000000000018', 'aaaaaaaa-0000-0000-0000-000000000018', 'EMP-S08', true, 0),
    ('00000000-0000-0000-0000-000000000019', 'aaaaaaaa-0000-0000-0000-000000000019', 'EMP-S09', true, 0),
    ('00000000-0000-0000-0000-00000000001a', 'aaaaaaaa-0000-0000-0000-00000000001a', 'EMP-S10', true, 0),
    ('00000000-0000-0000-0000-00000000001b', 'aaaaaaaa-0000-0000-0000-00000000001b', 'EMP-S11', true, 0),
    ('00000000-0000-0000-0000-00000000001c', 'aaaaaaaa-0000-0000-0000-00000000001c', 'EMP-S12', true, 0),
    ('00000000-0000-0000-0000-00000000001d', 'aaaaaaaa-0000-0000-0000-00000000001d', 'EMP-S13', true, 0)
ON CONFLICT (id) DO NOTHING;

-- Certifications at boundary distances (business date = 2026-08-12)
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT 'ee000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000016', ct.id, 'SWEEP-31D', '2025-01-01', '2026-09-12', 'Test', true, 0
  FROM certification_type ct WHERE ct.code = 'FIRST_AID' LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT 'ee000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000017', ct.id, 'SWEEP-30D', '2025-01-01', '2026-09-11', 'Test', true, 0
  FROM certification_type ct WHERE ct.code = 'FIRST_AID' LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT 'ee000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000018', ct.id, 'SWEEP-8D', '2025-01-01', '2026-08-20', 'Test', true, 0
  FROM certification_type ct WHERE ct.code = 'FIRST_AID' LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT 'ee000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000019', ct.id, 'SWEEP-7D', '2025-01-01', '2026-08-19', 'Test', true, 0
  FROM certification_type ct WHERE ct.code = 'GAS_SAFE' LIMIT 1
ON CONFLICT DO NOTHING;

INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT 'ee000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-00000000001a', ct.id, 'SWEEP-1D', '2025-01-01', '2026-08-13', 'Test', true, 0
  FROM certification_type ct WHERE ct.code = 'GAS_SAFE' LIMIT 1
ON CONFLICT DO NOTHING;

-- 0 days: expires on business date — counts as EXPIRED (daysToExpiry = 0)
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT 'ee000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-00000000001b', ct.id, 'SWEEP-0D', '2025-01-01', '2026-08-12', 'Test', true, 0
  FROM certification_type ct WHERE ct.code = 'GAS_SAFE' LIMIT 1
ON CONFLICT DO NOTHING;

-- -1 day: already expired yesterday — EXPIRED
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT 'ee000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-00000000001c', ct.id, 'SWEEP-MINUS1D', '2025-01-01', '2026-08-11', 'Test', true, 0
  FROM certification_type ct WHERE ct.code = 'GAS_SAFE' LIMIT 1
ON CONFLICT DO NOTHING;

-- Re-issued certification: original expired, then re-issued with a later expires_on.
-- The re-issue fixture has the current (new) expires_on = 2026-09-11 (WARNING cohort).
-- Test asserts: after inserting an alert_state row for the OLD validity_key,
-- the new validity_key is treated as a fresh period and alerts again.
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT 'ee000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-00000000001d', ct.id, 'SWEEP-REISSUE', '2025-01-01', '2026-09-11', 'Test', true, 0
  FROM certification_type ct WHERE ct.code = 'FIRST_AID' LIMIT 1
ON CONFLICT DO NOTHING;
