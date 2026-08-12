-- seed-analytics-workforce.sql
-- Anonymised roster, labour-time, and closure fixtures for WO-163 workforce KPI tests.
-- Covers 12 weeks of history with a mixed cohort:
--   TECH-FULL:     fully-utilized technician (≥90% utilization)
--   TECH-UNDER:    under-utilized technician (~30% utilization)
--   TECH-ONBOARD:  onboarded mid-window (week 3 of 12)
--   TECH-DEACT:    deactivated mid-window (active weeks 1-6 only)
--   TECH-ZERO:     technician with zero logged time (0% utilization, valid data)
--
-- UUID namespace: 00000000-0000-7163-00XX-000000000YYY
--   XX=cohort (01=FULL, 02=UNDER, 03=ONBOARD, 04=DEACT, 05=ZERO)
--   YYY=sequence
--
-- All data is anonymised: no names, emails, or phone numbers stored in analytics tables.
-- Technician names are in the technician table only and resolved at render time (BR-23).

-- ============================================================
-- Cohort technicians (minimal required columns)
-- ============================================================
INSERT INTO technician (id, user_id, full_name, active, timezone, version)
VALUES
    ('00000000-0000-7163-0001-000000000001', '00000000-0000-7163-9001-000000000001', 'T-WO163-FULL',    TRUE,  'UTC', 0),
    ('00000000-0000-7163-0002-000000000001', '00000000-0000-7163-9002-000000000001', 'T-WO163-UNDER',   TRUE,  'UTC', 0),
    ('00000000-0000-7163-0003-000000000001', '00000000-0000-7163-9003-000000000001', 'T-WO163-ONBOARD', TRUE,  'UTC', 0),
    ('00000000-0000-7163-0004-000000000001', '00000000-0000-7163-9004-000000000001', 'T-WO163-DEACT',   FALSE, 'UTC', 0),
    ('00000000-0000-7163-0005-000000000001', '00000000-0000-7163-9005-000000000001', 'T-WO163-ZERO',    TRUE,  'UTC', 0)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- Availability windows (recurring weekly shifts)
-- Mon-Fri 08:00-17:00 = 9h = 540 min/day × 5 days = 2700 min/week
-- ============================================================
-- TECH-FULL: active from 12 weeks ago
INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
SELECT
    gen_random_uuid(),
    '00000000-0000-7163-0001-000000000001',
    dow,
    '08:00',
    '17:00',
    (CURRENT_DATE - INTERVAL '84 days')::date,
    0
FROM unnest(ARRAY[1,2,3,4,5]) AS dow
ON CONFLICT DO NOTHING;

-- TECH-UNDER: active from 12 weeks ago
INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
SELECT
    gen_random_uuid(),
    '00000000-0000-7163-0002-000000000001',
    dow,
    '08:00',
    '17:00',
    (CURRENT_DATE - INTERVAL '84 days')::date,
    0
FROM unnest(ARRAY[1,2,3,4,5]) AS dow
ON CONFLICT DO NOTHING;

-- TECH-ONBOARD: onboarded 9 weeks ago (week 3 of 12-week window)
INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
SELECT
    gen_random_uuid(),
    '00000000-0000-7163-0003-000000000001',
    dow,
    '08:00',
    '17:00',
    (CURRENT_DATE - INTERVAL '63 days')::date,
    0
FROM unnest(ARRAY[1,2,3,4,5]) AS dow
ON CONFLICT DO NOTHING;

-- TECH-DEACT: active only for first 6 weeks (effective_to = 6 weeks ago)
INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, effective_to, version)
SELECT
    gen_random_uuid(),
    '00000000-0000-7163-0004-000000000001',
    dow,
    '08:00',
    '17:00',
    (CURRENT_DATE - INTERVAL '84 days')::date,
    (CURRENT_DATE - INTERVAL '42 days')::date,
    0
FROM unnest(ARRAY[1,2,3,4,5]) AS dow
ON CONFLICT DO NOTHING;

-- TECH-ZERO: has shifts but zero logged time
INSERT INTO technician_availability_window
    (id, technician_id, day_of_week, start_time, end_time, effective_from, version)
SELECT
    gen_random_uuid(),
    '00000000-0000-7163-0005-000000000001',
    dow,
    '08:00',
    '17:00',
    (CURRENT_DATE - INTERVAL '84 days')::date,
    0
FROM unnest(ARRAY[1,2,3,4,5]) AS dow
ON CONFLICT DO NOTHING;

-- ============================================================
-- Seed site + work orders (required for labour entries + closures)
-- ============================================================
INSERT INTO customer (id, name, version) VALUES
    ('00000000-0000-7163-8000-000000000001', 'WO163-Test-Customer', 0)
ON CONFLICT DO NOTHING;

INSERT INTO site (id, name, customer_id, version) VALUES
    ('00000000-0000-7163-8001-000000000001', 'WO163-Test-Site', '00000000-0000-7163-8000-000000000001', 0)
ON CONFLICT DO NOTHING;

-- Work orders for TECH-FULL (10 closures over 12 weeks)
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, fault_category, description, version)
SELECT
    gen_random_uuid(),
    'WO163-FULL-' || seq,
    'CLOSED',
    'HIGH',
    '00000000-0000-7163-8001-000000000001',
    '00000000-0000-7163-0001-000000000001',
    'ELECTRICAL',
    'Fixture work order for WO-163 workforce KPI test',
    0
FROM generate_series(1, 10) seq
ON CONFLICT DO NOTHING;

-- Work orders for TECH-UNDER (3 closures over 12 weeks)
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, fault_category, description, version)
SELECT
    gen_random_uuid(),
    'WO163-UNDER-' || seq,
    'CLOSED',
    'MEDIUM',
    '00000000-0000-7163-8001-000000000001',
    '00000000-0000-7163-0002-000000000001',
    'HVAC',
    'Fixture work order for WO-163 workforce KPI test',
    0
FROM generate_series(1, 3) seq
ON CONFLICT DO NOTHING;

-- ============================================================
-- Labour entries — only for TECH-FULL and TECH-UNDER; TECH-ZERO has none
-- ============================================================

-- TECH-FULL: ~90% utilization — 486 min/day × 5 days = 2430 min/week (vs 2700 shift)
INSERT INTO work_order_labour_entry (id, work_order_id, technician_id, minutes, created_at)
SELECT
    gen_random_uuid(),
    wo.id,
    '00000000-0000-7163-0001-000000000001',
    486,
    NOW() - (week_offset * INTERVAL '7 days') - INTERVAL '1 day'
FROM (SELECT id FROM work_order WHERE reference LIKE 'WO163-FULL-%' LIMIT 10) wo
CROSS JOIN generate_series(1, 10) week_offset
ON CONFLICT DO NOTHING;

-- TECH-UNDER: ~30% utilization — 162 min/day field time
INSERT INTO work_order_labour_entry (id, work_order_id, technician_id, minutes, created_at)
SELECT
    gen_random_uuid(),
    wo.id,
    '00000000-0000-7163-0002-000000000001',
    162,
    NOW() - (week_offset * INTERVAL '7 days') - INTERVAL '1 day'
FROM (SELECT id FROM work_order WHERE reference LIKE 'WO163-UNDER-%' LIMIT 3) wo
CROSS JOIN generate_series(1, 3) week_offset
ON CONFLICT DO NOTHING;

-- ============================================================
-- Analytics closure projection — records closure events for throughput metric
-- ============================================================
INSERT INTO analytics_closure_projection (id, work_order_id, fault_key, closed_at, maturity, matured_at, is_first_time_fix)
SELECT
    gen_random_uuid(),
    wo.id,
    'ELECTRICAL:GENERIC',
    NOW() - ((seq - 1) * INTERVAL '6 days') - INTERVAL '2 hours',
    'MATURED',
    NOW() - ((seq - 1) * INTERVAL '6 days') + INTERVAL '7 days',
    TRUE
FROM (SELECT id FROM work_order WHERE reference LIKE 'WO163-FULL-%' ORDER BY reference LIMIT 10) wo
JOIN generate_series(1, 10) seq ON TRUE
ON CONFLICT (work_order_id) DO NOTHING;

INSERT INTO analytics_closure_projection (id, work_order_id, fault_key, closed_at, maturity, matured_at, is_first_time_fix)
SELECT
    gen_random_uuid(),
    wo.id,
    'HVAC:GENERIC',
    NOW() - ((seq - 1) * INTERVAL '25 days') - INTERVAL '2 hours',
    'MATURED',
    NOW() - ((seq - 1) * INTERVAL '25 days') + INTERVAL '7 days',
    FALSE
FROM (SELECT id FROM work_order WHERE reference LIKE 'WO163-UNDER-%' ORDER BY reference LIMIT 3) wo
JOIN generate_series(1, 3) seq ON TRUE
ON CONFLICT (work_order_id) DO NOTHING;
