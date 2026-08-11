-- seed-analytics-quality.sql
-- Anonymized fixture data for first-time fix quality analytics tests (WO-164).
-- UUIDs use prefix 'bb000000-' for easy identification and cleanup.
-- Covers: 120+ days of closures, repeat visits at 1/29/30/31 days, 3-visit chain,
--         missing asset/fault WOs (UNCLASSIFIABLE bucket).
-- All data is synthetic — no real names, sites, or assets.

-- ============================================================
-- Cleanup prior runs
-- ============================================================
DELETE FROM repeat_visit_link WHERE id LIKE 'bb000000-%';
DELETE FROM analytics_closure_projection WHERE id LIKE 'bb000000-%';

-- ============================================================
-- Base reference data (idempotent — ON CONFLICT DO NOTHING)
-- Site and assets must exist before closure projections reference them.
-- ============================================================

INSERT INTO site (id, name, customer_id, version)
VALUES ('bb000000-0000-7001-8000-000000000001', 'Quality Fixture Site', NULL, 0)
ON CONFLICT DO NOTHING;

INSERT INTO asset (id, site_id, category, version)
VALUES
    ('bb000000-0000-7001-8000-000000000010', 'bb000000-0000-7001-8000-000000000001', 'HVAC',   0),
    ('bb000000-0000-7001-8000-000000000011', 'bb000000-0000-7001-8000-000000000001', 'ELEC',   0),
    ('bb000000-0000-7001-8000-000000000012', 'bb000000-0000-7001-8000-000000000001', 'PLUMB',  0)
ON CONFLICT DO NOTHING;

-- ============================================================
-- Closure projections — 120+ days of history
-- Maturity determined by closed_at relative to NOW():
--   WOs closed > 30 days ago = MATURED
--   WOs closed < 30 days ago = PROVISIONAL
-- ============================================================

-- === MATURED rows — closed 31 to 150 days ago ===
-- 12 first-time fix WOs on HVAC asset (FC-HVAC-001), spread across 120 days
INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, asset_category, fault_key, closed_at, maturity, matured_at, is_first_time_fix)
VALUES
    ('bb000000-0001-7000-8000-000000000001', 'bb000000-0011-7000-8000-000000000001',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '150 days', 'MATURED', NOW() - INTERVAL '120 days', TRUE),
    ('bb000000-0001-7000-8000-000000000002', 'bb000000-0011-7000-8000-000000000002',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '140 days', 'MATURED', NOW() - INTERVAL '110 days', TRUE),
    ('bb000000-0001-7000-8000-000000000003', 'bb000000-0011-7000-8000-000000000003',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '130 days', 'MATURED', NOW() - INTERVAL '100 days', TRUE),
    ('bb000000-0001-7000-8000-000000000004', 'bb000000-0011-7000-8000-000000000004',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '120 days', 'MATURED', NOW() - INTERVAL '90 days',  TRUE),
    ('bb000000-0001-7000-8000-000000000005', 'bb000000-0011-7000-8000-000000000005',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '110 days', 'MATURED', NOW() - INTERVAL '80 days',  TRUE),
    ('bb000000-0001-7000-8000-000000000006', 'bb000000-0011-7000-8000-000000000006',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '100 days', 'MATURED', NOW() - INTERVAL '70 days',  TRUE),
    ('bb000000-0001-7000-8000-000000000007', 'bb000000-0011-7000-8000-000000000007',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '90 days', 'MATURED',  NOW() - INTERVAL '60 days',  TRUE),
    ('bb000000-0001-7000-8000-000000000008', 'bb000000-0011-7000-8000-000000000008',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '80 days', 'MATURED',  NOW() - INTERVAL '50 days',  TRUE),
    ('bb000000-0001-7000-8000-000000000009', 'bb000000-0011-7000-8000-000000000009',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '70 days', 'MATURED',  NOW() - INTERVAL '40 days',  TRUE),
    ('bb000000-0001-7000-8000-000000000010', 'bb000000-0011-7000-8000-000000000010',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '60 days', 'MATURED',  NOW() - INTERVAL '30 days',  TRUE),
    ('bb000000-0001-7000-8000-000000000011', 'bb000000-0011-7000-8000-000000000011',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '50 days', 'MATURED',  NOW() - INTERVAL '20 days',  TRUE),
    ('bb000000-0001-7000-8000-000000000012', 'bb000000-0011-7000-8000-000000000012',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '40 days', 'MATURED',  NOW() - INTERVAL '10 days',  TRUE);

-- 3 MATURED repeat visits on ELEC asset (FC-ELEC-001) — marked not-first-time-fix
INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, asset_category, fault_key, closed_at, maturity, matured_at, is_first_time_fix)
VALUES
    -- First visit (becomes not-FTF after second visit links back)
    ('bb000000-0002-7000-8000-000000000001', 'bb000000-0021-7000-8000-000000000001',
     'bb000000-0000-7001-8000-000000000011', 'ELEC', 'FC-ELEC-001',
     NOW() - INTERVAL '90 days', 'MATURED', NOW() - INTERVAL '60 days', FALSE),
    -- Second visit — repeat at 29 days (within window)
    ('bb000000-0002-7000-8000-000000000002', 'bb000000-0021-7000-8000-000000000002',
     'bb000000-0000-7001-8000-000000000011', 'ELEC', 'FC-ELEC-001',
     NOW() - INTERVAL '61 days', 'MATURED', NOW() - INTERVAL '31 days', FALSE),
    -- Third visit — repeat at 1 day from second (within window)
    ('bb000000-0002-7000-8000-000000000003', 'bb000000-0021-7000-8000-000000000003',
     'bb000000-0000-7001-8000-000000000011', 'ELEC', 'FC-ELEC-001',
     NOW() - INTERVAL '60 days', 'MATURED', NOW() - INTERVAL '30 days', TRUE);

-- Repeat at exactly 30 days — NOT linked (outside window); earlier WO stays first-time-fix
INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, asset_category, fault_key, closed_at, maturity, matured_at, is_first_time_fix)
VALUES
    ('bb000000-0003-7000-8000-000000000001', 'bb000000-0031-7000-8000-000000000001',
     'bb000000-0000-7001-8000-000000000012', 'PLUMB', 'FC-PLUMB-001',
     NOW() - INTERVAL '70 days', 'MATURED', NOW() - INTERVAL '40 days', TRUE),
    -- Exactly 30 days later — should NOT be linked to above
    ('bb000000-0003-7000-8000-000000000002', 'bb000000-0031-7000-8000-000000000002',
     'bb000000-0000-7001-8000-000000000012', 'PLUMB', 'FC-PLUMB-001',
     NOW() - INTERVAL '40 days', 'MATURED', NOW() - INTERVAL '10 days', TRUE);

-- Repeat at 31 days — NOT linked
INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, asset_category, fault_key, closed_at, maturity, matured_at, is_first_time_fix)
VALUES
    ('bb000000-0004-7000-8000-000000000001', 'bb000000-0041-7000-8000-000000000001',
     'bb000000-0000-7001-8000-000000000012', 'PLUMB', 'FC-PLUMB-002',
     NOW() - INTERVAL '85 days', 'MATURED', NOW() - INTERVAL '55 days', TRUE),
    ('bb000000-0004-7000-8000-000000000002', 'bb000000-0041-7000-8000-000000000002',
     'bb000000-0000-7001-8000-000000000012', 'PLUMB', 'FC-PLUMB-002',
     NOW() - INTERVAL '54 days', 'MATURED', NOW() - INTERVAL '24 days', TRUE);

-- === PROVISIONAL rows — closed < 30 days ago ===
INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, asset_category, fault_key, closed_at, maturity, matured_at, is_first_time_fix)
VALUES
    ('bb000000-0005-7000-8000-000000000001', 'bb000000-0051-7000-8000-000000000001',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-001',
     NOW() - INTERVAL '5 days', 'PROVISIONAL', NOW() + INTERVAL '25 days', TRUE),
    ('bb000000-0005-7000-8000-000000000002', 'bb000000-0051-7000-8000-000000000002',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', 'FC-HVAC-002',
     NOW() - INTERVAL '10 days', 'PROVISIONAL', NOW() + INTERVAL '20 days', TRUE),
    ('bb000000-0005-7000-8000-000000000003', 'bb000000-0051-7000-8000-000000000003',
     'bb000000-0000-7001-8000-000000000011', 'ELEC', 'FC-ELEC-002',
     NOW() - INTERVAL '15 days', 'PROVISIONAL', NOW() + INTERVAL '15 days', FALSE);

-- === UNCLASSIFIABLE rows (null asset_id or null fault_key) ===
INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, asset_category, fault_key, closed_at, maturity, matured_at, is_first_time_fix)
VALUES
    -- Missing asset_id
    ('bb000000-0006-7000-8000-000000000001', 'bb000000-0061-7000-8000-000000000001',
     NULL, NULL, 'FC-HVAC-001',
     NOW() - INTERVAL '45 days', 'MATURED', NOW() - INTERVAL '15 days', TRUE),
    -- Missing fault_key
    ('bb000000-0006-7000-8000-000000000002', 'bb000000-0061-7000-8000-000000000002',
     'bb000000-0000-7001-8000-000000000010', 'HVAC', NULL,
     NOW() - INTERVAL '50 days', 'MATURED', NOW() - INTERVAL '20 days', TRUE);

-- ============================================================
-- Repeat visit links (for the ELEC 3-visit chain above)
-- ============================================================
INSERT INTO repeat_visit_link
    (id, earlier_work_order_id, later_work_order_id, asset_id, fault_key, days_between, linked_at)
VALUES
    -- First → Second (29 days apart)
    ('bb000000-0007-7000-8000-000000000001',
     'bb000000-0021-7000-8000-000000000001',
     'bb000000-0021-7000-8000-000000000002',
     'bb000000-0000-7001-8000-000000000011', 'FC-ELEC-001', 29,
     NOW() - INTERVAL '61 days'),
    -- Second → Third (1 day apart)
    ('bb000000-0007-7000-8000-000000000002',
     'bb000000-0021-7000-8000-000000000002',
     'bb000000-0021-7000-8000-000000000003',
     'bb000000-0000-7001-8000-000000000011', 'FC-ELEC-001', 1,
     NOW() - INTERVAL '60 days');
