-- =============================================================================
-- V121: Test fixtures for WO-119 certification registry tests
--
-- Requires: V100 (technicians: TECH_1=00000000-...-0011, TECH_2=00000000-...-0012)
--           V39  (certification_type and technician_certification tables)
--
-- Fixture scenarios (AC-13):
--   TECH_1  — current regulated certification (GAS_SAFE, expires 2099-12-31)
--   TECH_2  — expiring within 30 days (expires today+15, non-regulated FIRST_AID)
--   TECH_3  — expired regulated (GAS_SAFE expired 2020-01-01)
--   TECH_4  — expired non-regulated (FIRST_AID expired 2020-01-01)
--   TECH_5  — no certifications at all
-- =============================================================================

-- Certification types seeded by V39 via ON CONFLICT DO NOTHING;
-- We reference them by code via a subquery.

-- Current regulated — TECH_1
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT
    'cc000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    ct.id,
    'GS-REG-TECH1',
    '2024-01-01',
    '2099-12-31',
    'Gas Safe Register',
    true,
    0
FROM certification_type ct WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- Expiring within 30 days (non-regulated) — TECH_2
-- Actual date is relative; we insert a cert expiring 2026-08-26 (15 days from 2026-08-11).
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT
    'cc000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000012',
    ct.id,
    'FA-EXPIRING-TECH2',
    '2023-08-26',
    '2026-08-26',
    'St John Ambulance',
    true,
    0
FROM certification_type ct WHERE ct.code = 'FIRST_AID'
ON CONFLICT DO NOTHING;

-- Expired regulated — we need TECH_3 and TECH_4 technician rows
-- Use TECH_1's user as base but with new IDs (test-only technicians)
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000013', 'tech3@example.com', '$2a$10$placeholder.hash.tech3..........', 'Test Tech 3 (expired regulated)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000014', 'tech4@example.com', '$2a$10$placeholder.hash.tech4..........', 'Test Tech 4 (expired non-regulated)', true, 0),
    ('aaaaaaaa-0000-0000-0000-000000000015', 'tech5@example.com', '$2a$10$placeholder.hash.tech5..........', 'Test Tech 5 (no certifications)', true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, employee_no, is_active, version) VALUES
    ('00000000-0000-0000-0000-000000000013', 'aaaaaaaa-0000-0000-0000-000000000013', 'EMP-003', true, 0),
    ('00000000-0000-0000-0000-000000000014', 'aaaaaaaa-0000-0000-0000-000000000014', 'EMP-004', true, 0),
    ('00000000-0000-0000-0000-000000000015', 'aaaaaaaa-0000-0000-0000-000000000015', 'EMP-005', true, 0)
ON CONFLICT (id) DO NOTHING;

-- Expired regulated — TECH_3
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT
    'cc000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000013',
    ct.id,
    'GS-EXPIRED-TECH3',
    '2019-01-01',
    '2020-01-01',
    'Gas Safe Register',
    true,
    0
FROM certification_type ct WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- Expired non-regulated — TECH_4
INSERT INTO technician_certification (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, issuing_body, active, version)
SELECT
    'cc000000-0000-0000-0000-000000000004',
    '00000000-0000-0000-0000-000000000014',
    ct.id,
    'FA-EXPIRED-TECH4',
    '2019-01-01',
    '2020-01-01',
    'British Red Cross',
    true,
    0
FROM certification_type ct WHERE ct.code = 'FIRST_AID'
ON CONFLICT DO NOTHING;

-- TECH_5 — no certifications (no insert needed)
