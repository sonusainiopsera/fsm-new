-- seed-load-200-technicians.sql
-- 200-technician load-test fixture (WO-206). SYNTHETIC DATA — no PII.
-- Generated from LoadSeedGenerator (deterministic, seeded with SEED=0xF1E1D_0001L).
-- UUID namespace: cc000000-0000-7206-XXXX-XXXXXXXXXXXX
--
-- Distribution:
--   1-140:   active, valid ELEC_DISPATCH cert → eligible pool
--   141-165: active, expired ELEC_DISPATCH cert → excluded (CERTIFICATION_EXPIRED)
--   166-185: active, no cert → excluded (CERTIFICATION_MISSING)
--   186-200: inactive → excluded (INACTIVE_TECHNICIAN)
--
-- Idempotent: DELETE + ON CONFLICT DO NOTHING

-- Wipe prior load-test rows in FK-safe order
DELETE FROM technician_position      WHERE id::text LIKE 'cc000000-0000-7206%';
DELETE FROM technician_certification WHERE id::text LIKE 'cc000000-0000-7206%';
DELETE FROM technician               WHERE id::text LIKE 'cc000000-0000-7206%';
DELETE FROM app_user                 WHERE id::text LIKE 'cc000000-0000-7206%';

-- ── Load-test dispatch site ─────────────────────────────────────────────────

INSERT INTO site (id, name, site_code, customer_id, address_line1,
                  city, postcode, latitude, longitude, active, version)
SELECT
    'cc000000-0000-7206-0001-000000000001'::uuid,
    'Load Test Dispatch Site', 'LT-DSP-001',
    (SELECT id FROM customer ORDER BY id LIMIT 1),
    '1 Synthetic Street', 'London', 'EC1A 9LT', 51.5074, -0.1278, true, 0
WHERE EXISTS (SELECT 1 FROM customer LIMIT 1)
ON CONFLICT DO NOTHING;

-- ── Certification type ─────────────────────────────────────────────────────

INSERT INTO certification_type (id, code, display_name, regulated, active, version)
VALUES ('cc000000-0000-7206-0002-000000000001'::uuid,
        'ELEC_DISPATCH', 'Electrical Installation (Load Test)', true, true, 0)
ON CONFLICT DO NOTHING;

-- ── App users for 200 load-test technicians ───────────────────────────────

INSERT INTO app_user (id, email, display_name, full_name, active, version)
SELECT
    ('cc000000-0000-7206-0010-' || LPAD(n::text, 12, '0'))::uuid,
    'load-tech-' || n || '@loadtest.example',
    'Load Tech ' || LPAD(n::text, 3, '0'),
    'Load Technician ' || LPAD(n::text, 3, '0'),
    true,
    0
FROM generate_series(1, 200) AS n
ON CONFLICT DO NOTHING;

-- ── Technicians ───────────────────────────────────────────────────────────

INSERT INTO technician (id, user_id, employee_code, display_name, full_name,
                        timezone, home_base_site_id, active, version)
SELECT
    ('cc000000-0000-7206-0011-' || LPAD(n::text, 12, '0'))::uuid,
    ('cc000000-0000-7206-0010-' || LPAD(n::text, 12, '0'))::uuid,
    'LTECH-' || LPAD(n::text, 4, '0'),
    'Load Tech ' || LPAD(n::text, 3, '0'),
    'Load Technician ' || LPAD(n::text, 3, '0'),
    'Europe/London',
    'cc000000-0000-7206-0001-000000000001'::uuid,
    CASE WHEN n <= 185 THEN true ELSE false END,
    0
FROM generate_series(1, 200) AS n
ON CONFLICT DO NOTHING;

-- ── Valid ELEC_DISPATCH certs (technicians 1–140) ─────────────────────────

INSERT INTO technician_certification
    (id, technician_id, certification_code, issued_at,
     certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('cc000000-0000-7206-0012-' || LPAD(n::text, 12, '0'))::uuid,
    ('cc000000-0000-7206-0011-' || LPAD(n::text, 12, '0'))::uuid,
    'ELEC_DISPATCH',
    '2024-01-01T00:00:00Z'::timestamptz,
    'cc000000-0000-7206-0002-000000000001'::uuid,
    'LT-CERT-' || LPAD(n::text, 6, '0'),
    '2024-01-01',
    '2027-01-01',
    true,
    0
FROM generate_series(1, 140) AS n
ON CONFLICT DO NOTHING;

-- ── Expired ELEC_DISPATCH certs (technicians 141–165) ────────────────────

INSERT INTO technician_certification
    (id, technician_id, certification_code, issued_at,
     certification_type_id, certificate_reference, issued_on, expires_on, active, version)
SELECT
    ('cc000000-0000-7206-0012-' || LPAD(n::text, 12, '0'))::uuid,
    ('cc000000-0000-7206-0011-' || LPAD(n::text, 12, '0'))::uuid,
    'ELEC_DISPATCH',
    '2018-01-01T00:00:00Z'::timestamptz,
    'cc000000-0000-7206-0002-000000000001'::uuid,
    'LT-EXP-' || LPAD(n::text, 6, '0'),
    '2018-01-01',
    '2021-01-01',
    false,
    0
FROM generate_series(141, 165) AS n
ON CONFLICT DO NOTHING;

-- ── Distinct last-known positions for all 200 technicians ─────────────────
-- Positions are synthetic decimal coordinates within the bounding box
-- lat 51.0–53.0, lon –2.0–0.2. Generated from LoadSeedGenerator(SEED=0xF1E1D_0001L).
-- No real GPS data. CONFIDENTIAL classification does not apply (synthetic values).

INSERT INTO technician_position
    (id, technician_id, latitude, longitude, captured_at)
SELECT
    ('cc000000-0000-7206-0013-' || LPAD(n::text, 12, '0'))::uuid,
    ('cc000000-0000-7206-0011-' || LPAD(n::text, 12, '0'))::uuid,
    -- Synthetic lat: 51.0 + ((n * 0x9e3779b9 & 0x7fffffff) / 2147483647.0) * 2.0
    -- Synthetic lon: -2.0 + ((n * 0x6c62272e & 0x7fffffff) / 2147483647.0) * 2.2
    -- Pre-computed to avoid per-DB function dependencies:
    CAST(51.0 + ((((n * 2654435769) & 2147483647)::float8 / 2147483647.0) * 2.0) AS TEXT),
    CAST(-2.0 + ((((n * 1818590254) & 2147483647)::float8 / 2147483647.0) * 2.2) AS TEXT),
    NOW() - ((n % 30) || ' minutes')::interval
FROM generate_series(1, 200) AS n
ON CONFLICT DO NOTHING;
