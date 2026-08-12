-- =============================================================================
-- V131: Dispatch eligibility test fixtures (WO-133)
--
-- Service window under test: 2026-08-17T09:00:00Z – 2026-08-17T16:00:00Z (Monday)
-- Required cert: GAS_SAFE, site = London (51.5074, -0.1278), maxReachKm = 100
--
-- Named technicians (IDs 5001-5022) — referenced by IT assertions:
--   5001-5005  ELIGIBLE          current GAS_SAFE, Monday window, London (5 techs)
--   5006-5008  CERT_MISSING      no GAS_SAFE cert at all (3 techs)
--   5009-5011  CERT_EXPIRED      GAS_SAFE expired 2026-08-11 (3 techs)
--   5012-5014  UNAVAIL_ABSENCE   absent all day 2026-08-17 (3 techs)
--   5015-5017  UNAVAIL_NOSHIFT   no availability windows (3 techs)
--   5018-5020  OOR               home base in Sydney  ~16 800 km away (3 techs)
--   5021-5022  INACTIVE          is_active = false, not returned by repo query (2 techs)
--
-- Bulk eligible technicians (IDs 5023-5172) — 150 extra for statement-count assertion
-- =============================================================================

-- ── Shared customer + sites ───────────────────────────────────────────────────

INSERT INTO customer (id, name, is_active, version) VALUES
    ('cc000000-0000-0000-0000-000000000001', 'Dispatch Test Org', true, 0)
ON CONFLICT (id) DO NOTHING;

-- Near site: central London
INSERT INTO site (id, customer_id, name, latitude, longitude, is_active, version) VALUES
    ('55000000-0000-0000-0000-000000000001', 'cc000000-0000-0000-0000-000000000001',
     'London HQ', 51.5074, -0.1278, true, 0)
ON CONFLICT (id) DO NOTHING;

-- Far site: Sydney — used as home_base for OOR group
INSERT INTO site (id, customer_id, name, latitude, longitude, is_active, version) VALUES
    ('55000000-0000-0000-0000-000000000002', 'cc000000-0000-0000-0000-000000000001',
     'Sydney Branch', -33.8688, 151.2093, true, 0)
ON CONFLICT (id) DO NOTHING;

-- ── Named app_users (5001-5022) ───────────────────────────────────────────────

INSERT INTO app_user (id, email, password_hash, display_name, is_active, version)
SELECT
    ('bb000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    'dispatch-tech-' || i || '@test.example',
    '$2a$10$placeholder.dispatch.hash.',
    'Dispatch Tech ' || i,
    CASE WHEN i IN (5021, 5022) THEN false ELSE true END,
    0
FROM generate_series(5001, 5022) AS i
ON CONFLICT (id) DO NOTHING;

-- ── Named technicians (5001-5022) ─────────────────────────────────────────────

INSERT INTO technician (id, user_id, employee_no, is_active, timezone, home_base_site_id, version)
SELECT
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ('bb000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    'DISP-' || i,
    CASE WHEN i IN (5021, 5022) THEN false ELSE true END,
    CASE WHEN i BETWEEN 5018 AND 5020 THEN 'Australia/Sydney' ELSE 'Europe/London' END,
    CASE WHEN i BETWEEN 5018 AND 5020
         THEN '55000000-0000-0000-0000-000000000002'::uuid
         ELSE '55000000-0000-0000-0000-000000000001'::uuid
    END,
    0
FROM generate_series(5001, 5022) AS i
ON CONFLICT (id) DO NOTHING;

-- ── GAS_SAFE certifications ───────────────────────────────────────────────────

-- Eligible group (5001-5005) + availability groups (5012-5014, 5015-5017) + OOR group (5018-5020):
-- all get a current GAS_SAFE cert (expires far future)
INSERT INTO technician_certification
    (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('ee000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ct.id,
    'GS-DISP-' || i,
    '2024-01-01',
    '2099-12-31',
    true,
    0
FROM generate_series(5001, 5005) AS i,
     certification_type ct
WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- CERT_EXPIRED group (5009-5011): expired 2026-08-11 (strictly before service window start 2026-08-17)
INSERT INTO technician_certification
    (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('ee000000-0000-0000-0001-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ct.id,
    'GS-EXPIRED-' || i,
    '2023-01-01',
    '2026-08-11',
    true,
    0
FROM generate_series(5009, 5011) AS i,
     certification_type ct
WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- UNAVAIL_ABSENCE group (5012-5014): current GAS_SAFE cert
INSERT INTO technician_certification
    (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('ee000000-0000-0000-0002-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ct.id,
    'GS-ABS-' || i,
    '2024-01-01',
    '2099-12-31',
    true,
    0
FROM generate_series(5012, 5014) AS i,
     certification_type ct
WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- UNAVAIL_NOSHIFT group (5015-5017): current GAS_SAFE cert, but no windows (inserted below)
INSERT INTO technician_certification
    (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('ee000000-0000-0000-0003-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ct.id,
    'GS-NOSHIFT-' || i,
    '2024-01-01',
    '2099-12-31',
    true,
    0
FROM generate_series(5015, 5017) AS i,
     certification_type ct
WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- OOR group (5018-5020): current GAS_SAFE cert
INSERT INTO technician_certification
    (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('ee000000-0000-0000-0004-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ct.id,
    'GS-OOR-' || i,
    '2024-01-01',
    '2099-12-31',
    true,
    0
FROM generate_series(5018, 5020) AS i,
     certification_type ct
WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

-- ── Availability windows ──────────────────────────────────────────────────────
-- Groups that need Monday 08:00-17:00 windows: eligible (5001-5005), absence (5012-5014), OOR (5018-5020)
-- NOSHIFT group (5015-5017) intentionally gets NO windows

INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
SELECT
    ('ff000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    1,           -- MONDAY (ISO: 1=Mon)
    '08:00:00',
    '17:00:00',
    '2026-01-01',
    0
FROM (
    SELECT * FROM generate_series(5001, 5005)
    UNION ALL SELECT * FROM generate_series(5012, 5014)
    UNION ALL SELECT * FROM generate_series(5018, 5020)
) AS s(i)
ON CONFLICT DO NOTHING;

-- CERT_MISSING (5006-5008) and CERT_EXPIRED (5009-5011) also need windows so they get
-- past the absence check before hitting the cert check.  In our filter, cert check comes
-- BEFORE availability — but we still give them windows to reflect realistic data.
INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
SELECT
    ('ff000000-0000-0000-0001-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    1,
    '08:00:00',
    '17:00:00',
    '2026-01-01',
    0
FROM generate_series(5006, 5011) AS i
ON CONFLICT DO NOTHING;

-- ── Absences ──────────────────────────────────────────────────────────────────
-- UNAVAIL_ABSENCE group (5012-5014): all-day absence on 2026-08-17

INSERT INTO technician_absence
    (id, technician_id, starts_at, ends_at, reason, version)
SELECT
    ('aa000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    '2026-08-17 00:00:00+00',
    '2026-08-18 00:00:00+00',
    'ANNUAL_LEAVE',
    0
FROM generate_series(5012, 5014) AS i
ON CONFLICT DO NOTHING;

-- =============================================================================
-- Bulk eligible technicians (5023-5172) — 150 extra for statement-count proof
-- All have: current GAS_SAFE, Monday window, London home base, active = true
-- =============================================================================

INSERT INTO app_user (id, email, password_hash, display_name, is_active, version)
SELECT
    ('bb000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    'dispatch-bulk-' || i || '@test.example',
    '$2a$10$placeholder.dispatch.hash.',
    'Dispatch Bulk Tech ' || i,
    true,
    0
FROM generate_series(5023, 5172) AS i
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, employee_no, is_active, timezone, home_base_site_id, version)
SELECT
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ('bb000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    'BULK-' || i,
    true,
    'Europe/London',
    '55000000-0000-0000-0000-000000000001'::uuid,
    0
FROM generate_series(5023, 5172) AS i
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician_certification
    (id, technician_id, certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('ee000000-0001-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ct.id,
    'GS-BULK-' || i,
    '2024-01-01',
    '2099-12-31',
    true,
    0
FROM generate_series(5023, 5172) AS i,
     certification_type ct
WHERE ct.code = 'GAS_SAFE'
ON CONFLICT DO NOTHING;

INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
SELECT
    ('ff000000-0001-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    ('dd000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::uuid,
    1,
    '08:00:00',
    '17:00:00',
    '2026-01-01',
    0
FROM generate_series(5023, 5172) AS i
ON CONFLICT DO NOTHING;
