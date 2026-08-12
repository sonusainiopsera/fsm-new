-- =============================================================================
-- Dispatch eligibility 200-technician fixture (WO-133)
-- =============================================================================
-- UUID namespace: 00000000-0000-7033-XXXX-XXXXXXXXXXXX
-- IDEMPOTENT: all inserts use ON CONFLICT DO NOTHING.
--
-- Distribution (200 total):
--   IDs 1–50:   active, valid ELEC_DISPATCH cert, Thursday shift, London home-base → ELIGIBLE
--   IDs 51–100: active, ELEC_DISPATCH cert expired          → CERTIFICATION_EXPIRED
--   IDs 101–150: active, no ELEC_DISPATCH cert at all       → CERTIFICATION_MISSING
--   IDs 151–200: inactive flag                              → INACTIVE_TECHNICIAN
-- =============================================================================

-- ── Dispatch test sites (London-area with geocoordinates) ───────────────────

INSERT INTO site (id, name, site_code, customer_id, address_line1,
                  city, postcode, latitude, longitude, active, version)
SELECT
    '00000000-0000-7033-0001-000000000001'::uuid,
    'Dispatch Test Site Central', 'DSP-CENTRAL',
    (SELECT id FROM customer LIMIT 1),
    '1 Central Street', 'London', 'EC1A 1BB', 51.5074, -0.1278, true, 0
WHERE EXISTS (SELECT 1 FROM customer LIMIT 1)
ON CONFLICT DO NOTHING;

INSERT INTO site (id, name, site_code, customer_id, address_line1,
                  city, postcode, latitude, longitude, active, version)
SELECT
    '00000000-0000-7033-0001-000000000002'::uuid,
    'Dispatch Home Base North', 'DSP-NORTH',
    (SELECT id FROM customer LIMIT 1),
    '10 North Road', 'London', 'N1 9GU', 51.53, -0.10, true, 0
WHERE EXISTS (SELECT 1 FROM customer LIMIT 1)
ON CONFLICT DO NOTHING;

-- ── Certification type for dispatch tests ────────────────────────────────────

INSERT INTO certification_type (id, code, display_name, regulated, active, version)
VALUES
    ('00000000-0000-7033-0002-000000000001'::uuid,
     'ELEC_DISPATCH', 'Electrical Installation (Dispatch Test)', true, true, 0)
ON CONFLICT DO NOTHING;

-- ── App users for 200 dispatch technicians ────────────────────────────────────
-- Batch 1 (1–50): eligible (active users)
-- Batch 2 (51–100): active users, expired cert
-- Batch 3 (101–150): active users, missing cert
-- Batch 4 (151–200): inactive flag still needs a user

INSERT INTO app_user (id, email, display_name, full_name, active, version)
SELECT
    ('00000000-0000-7033-0010-' || LPAD(n::text, 12, '0'))::uuid,
    'dispatch-tech-' || n || '@example.test',
    'Dispatch Tech ' || n,
    'Dispatch Tech ' || n,
    true,
    0
FROM generate_series(1, 200) AS n
ON CONFLICT DO NOTHING;

-- ── Technicians (1–50: active eligible, home-base near London) ────────────────

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, home_base_site_id, active, version)
SELECT
    ('00000000-0000-7033-0011-' || LPAD(n::text, 12, '0'))::uuid,
    ('00000000-0000-7033-0010-' || LPAD(n::text, 12, '0'))::uuid,
    'DTECH-' || LPAD(n::text, 4, '0'),
    'Dispatch Tech ' || n,
    'Dispatch Tech ' || n,
    'Europe/London',
    '00000000-0000-7033-0001-000000000002'::uuid,
    true,
    0
FROM generate_series(1, 50) AS n
ON CONFLICT DO NOTHING;

-- ── Technicians (51–100: active, expired cert) ──────────────────────────────

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, home_base_site_id, active, version)
SELECT
    ('00000000-0000-7033-0011-' || LPAD(n::text, 12, '0'))::uuid,
    ('00000000-0000-7033-0010-' || LPAD(n::text, 12, '0'))::uuid,
    'DTECH-' || LPAD(n::text, 4, '0'),
    'Dispatch Tech ' || n,
    'Dispatch Tech ' || n,
    'Europe/London',
    '00000000-0000-7033-0001-000000000002'::uuid,
    true,
    0
FROM generate_series(51, 100) AS n
ON CONFLICT DO NOTHING;

-- ── Technicians (101–150: active, missing cert) ──────────────────────────────

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, home_base_site_id, active, version)
SELECT
    ('00000000-0000-7033-0011-' || LPAD(n::text, 12, '0'))::uuid,
    ('00000000-0000-7033-0010-' || LPAD(n::text, 12, '0'))::uuid,
    'DTECH-' || LPAD(n::text, 4, '0'),
    'Dispatch Tech ' || n,
    'Dispatch Tech ' || n,
    'Europe/London',
    '00000000-0000-7033-0001-000000000002'::uuid,
    true,
    0
FROM generate_series(101, 150) AS n
ON CONFLICT DO NOTHING;

-- ── Technicians (151–200: inactive) ─────────────────────────────────────────

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, home_base_site_id, active, version)
SELECT
    ('00000000-0000-7033-0011-' || LPAD(n::text, 12, '0'))::uuid,
    ('00000000-0000-7033-0010-' || LPAD(n::text, 12, '0'))::uuid,
    'DTECH-' || LPAD(n::text, 4, '0'),
    'Dispatch Tech ' || n,
    'Dispatch Tech ' || n,
    'Europe/London',
    '00000000-0000-7033-0001-000000000002'::uuid,
    false,
    0
FROM generate_series(151, 200) AS n
ON CONFLICT DO NOTHING;

-- ── Certifications (1–50: valid, expires 2030-01-01) ─────────────────────────

INSERT INTO technician_certification
    (id, technician_id, certification_code, issued_at,
     certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('00000000-0000-7033-0012-' || LPAD(n::text, 12, '0'))::uuid,
    ('00000000-0000-7033-0011-' || LPAD(n::text, 12, '0'))::uuid,
    'ELEC_DISPATCH',
    '2024-01-01T00:00:00Z'::timestamptz,
    '00000000-0000-7033-0002-000000000001'::uuid,
    'CERT-VALID-' || n,
    '2024-01-01'::date,
    '2030-01-01'::date,
    true,
    0
FROM generate_series(1, 50) AS n
ON CONFLICT DO NOTHING;

-- ── Certifications (51–100: expired 2020-01-01) ───────────────────────────────

INSERT INTO technician_certification
    (id, technician_id, certification_code, issued_at,
     certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('00000000-0000-7033-0012-' || LPAD(n::text, 12, '0'))::uuid,
    ('00000000-0000-7033-0011-' || LPAD(n::text, 12, '0'))::uuid,
    'ELEC_DISPATCH',
    '2018-01-01T00:00:00Z'::timestamptz,
    '00000000-0000-7033-0002-000000000001'::uuid,
    'CERT-EXPIRED-' || n,
    '2018-01-01'::date,
    '2020-01-01'::date,
    true,
    0
FROM generate_series(51, 100) AS n
ON CONFLICT DO NOTHING;

-- (101–150: no cert row — intentionally absent to trigger CERTIFICATION_MISSING)
-- (151–200: no cert row — inactive anyway, excluded before cert check)

-- ── Availability windows (1–150: Thursday 08:00–18:00, effective 2024-01-01) ──
-- (151–200 inactive — not needed but would not affect test result)

INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
SELECT
    ('00000000-0000-7033-0013-' || LPAD(n::text, 12, '0'))::uuid,
    ('00000000-0000-7033-0011-' || LPAD(n::text, 12, '0'))::uuid,
    4,                          -- Thursday (ISO 4)
    '08:00:00'::time,
    '18:00:00'::time,
    '2024-01-01'::date,
    0
FROM generate_series(1, 150) AS n
ON CONFLICT DO NOTHING;
