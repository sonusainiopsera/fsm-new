-- V115__quality_fixtures.sql
-- Test fixtures for WO-164 first-time fix quality metrics.
--
-- Assets used: 20000000-0000-0000-0000-000000000001 (HVAC Unit A1, from V100)
--              20000000-0000-0000-0000-000000000002 (Boiler A2, from V100)
--
-- Work order UUIDs: 80000000-0000-0000-0000-000000000XXX range (quality prefix)
--
-- Scenarios seeded:
--   A) 120-day baseline FTF history (10 matured closures, all first-time-fix)
--   B) Repeat visit at 1 day (linked pair)
--   C) Repeat visit at 29 days (linked — inside window)
--   D) Repeat visit at exactly 30 days (NOT linked — outside window)
--   E) Three-visit chain A→B→C
--   F) UNCLASSIFIABLE work orders (missing asset or fault identity)

-- -------------------------------------------------------------------------
-- A) 10 standalone closures on HVAC asset, fault FC-001, 120 days ago
--    All MATURED (matured_at in the past), all first_time_fix=true
-- -------------------------------------------------------------------------
DO $$
DECLARE
    i INT;
    wo_id UUID;
    closed TIMESTAMPTZ;
BEGIN
    FOR i IN 1..10 LOOP
        wo_id   := ('80000000-0000-0000-0000-' || lpad(i::text, 12, '0'))::UUID;
        closed  := now() - (120 + i) * INTERVAL '1 day';

        -- work_order row (state=CLOSED, asset assigned, fault_code set)
        INSERT INTO work_order (id, tenant_id, title, description, state, priority,
                                asset_id, fault_code, fault_category,
                                created_at, updated_at, version)
        VALUES (wo_id,
                '00000000-0000-0000-0000-000000000001',  -- default test tenant
                'Quality fixture WO ' || i, 'Auto-generated', 'CLOSED', 'MEDIUM',
                '20000000-0000-0000-0000-000000000001',
                'FC-001', 'COOLING',
                closed, closed, 0)
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO analytics_closure_projection
            (id, work_order_id, asset_id, fault_key, is_first_time_fix,
             maturity, closed_at, matured_at, created_at, updated_at)
        VALUES (gen_random_uuid(), wo_id,
                '20000000-0000-0000-0000-000000000001',
                'FC-001', TRUE,
                'MATURED',
                closed,
                closed + INTERVAL '30 days',
                now(), now())
        ON CONFLICT (work_order_id) DO NOTHING;
    END LOOP;
END $$;

-- -------------------------------------------------------------------------
-- B) Repeat visit at 1 day — linked pair (HVAC, FC-002)
-- -------------------------------------------------------------------------
INSERT INTO work_order (id, tenant_id, title, description, state, priority,
                        asset_id, fault_code, created_at, updated_at, version)
VALUES
    ('80000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001',
     'Quality B1 (first)', '', 'CLOSED', 'LOW',
     '20000000-0000-0000-0000-000000000001', 'FC-002',
     now() - INTERVAL '62 days', now() - INTERVAL '62 days', 0),
    ('80000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000001',
     'Quality B2 (repeat +1d)', '', 'CLOSED', 'LOW',
     '20000000-0000-0000-0000-000000000001', 'FC-002',
     now() - INTERVAL '61 days', now() - INTERVAL '61 days', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, fault_key, is_first_time_fix,
     maturity, closed_at, matured_at, created_at, updated_at)
VALUES
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000101',
     '20000000-0000-0000-0000-000000000001', 'FC-002', FALSE,
     'MATURED', now() - INTERVAL '62 days', now() - INTERVAL '32 days', now(), now()),
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000102',
     '20000000-0000-0000-0000-000000000001', 'FC-002', FALSE,
     'MATURED', now() - INTERVAL '61 days', now() - INTERVAL '31 days', now(), now())
ON CONFLICT (work_order_id) DO NOTHING;

INSERT INTO repeat_visit_link
    (id, earlier_work_order_id, later_work_order_id, asset_id, fault_key, days_between, linked_at)
VALUES (gen_random_uuid(),
        '80000000-0000-0000-0000-000000000101',
        '80000000-0000-0000-0000-000000000102',
        '20000000-0000-0000-0000-000000000001',
        'FC-002', 1, now())
ON CONFLICT (earlier_work_order_id, later_work_order_id) DO NOTHING;

-- -------------------------------------------------------------------------
-- C) Repeat visit at 29 days — inside window, linked (Boiler, FC-003)
-- -------------------------------------------------------------------------
INSERT INTO work_order (id, tenant_id, title, description, state, priority,
                        asset_id, fault_code, created_at, updated_at, version)
VALUES
    ('80000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000001',
     'Quality C1 (first)', '', 'CLOSED', 'MEDIUM',
     '20000000-0000-0000-0000-000000000002', 'FC-003',
     now() - INTERVAL '90 days', now() - INTERVAL '90 days', 0),
    ('80000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000001',
     'Quality C2 (repeat +29d)', '', 'CLOSED', 'MEDIUM',
     '20000000-0000-0000-0000-000000000002', 'FC-003',
     now() - INTERVAL '61 days', now() - INTERVAL '61 days', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, fault_key, is_first_time_fix,
     maturity, closed_at, matured_at, created_at, updated_at)
VALUES
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000201',
     '20000000-0000-0000-0000-000000000002', 'FC-003', FALSE,
     'MATURED', now() - INTERVAL '90 days', now() - INTERVAL '60 days', now(), now()),
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000202',
     '20000000-0000-0000-0000-000000000002', 'FC-003', FALSE,
     'MATURED', now() - INTERVAL '61 days', now() - INTERVAL '31 days', now(), now())
ON CONFLICT (work_order_id) DO NOTHING;

INSERT INTO repeat_visit_link
    (id, earlier_work_order_id, later_work_order_id, asset_id, fault_key, days_between, linked_at)
VALUES (gen_random_uuid(),
        '80000000-0000-0000-0000-000000000201',
        '80000000-0000-0000-0000-000000000202',
        '20000000-0000-0000-0000-000000000002',
        'FC-003', 29, now())
ON CONFLICT (earlier_work_order_id, later_work_order_id) DO NOTHING;

-- -------------------------------------------------------------------------
-- D) Gap at exactly 30 days — NOT linked (Boiler, FC-004)
-- -------------------------------------------------------------------------
INSERT INTO work_order (id, tenant_id, title, description, state, priority,
                        asset_id, fault_code, created_at, updated_at, version)
VALUES
    ('80000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000001',
     'Quality D1', '', 'CLOSED', 'LOW',
     '20000000-0000-0000-0000-000000000002', 'FC-004',
     now() - INTERVAL '91 days', now() - INTERVAL '91 days', 0),
    ('80000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000001',
     'Quality D2 (30d exactly, not linked)', '', 'CLOSED', 'LOW',
     '20000000-0000-0000-0000-000000000002', 'FC-004',
     now() - INTERVAL '61 days', now() - INTERVAL '61 days', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, fault_key, is_first_time_fix,
     maturity, closed_at, matured_at, created_at, updated_at)
VALUES
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000301',
     '20000000-0000-0000-0000-000000000002', 'FC-004', TRUE,
     'MATURED', now() - INTERVAL '91 days', now() - INTERVAL '61 days', now(), now()),
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000302',
     '20000000-0000-0000-0000-000000000002', 'FC-004', TRUE,
     'MATURED', now() - INTERVAL '61 days', now() - INTERVAL '31 days', now(), now())
ON CONFLICT (work_order_id) DO NOTHING;

-- No repeat_visit_link row — 30-day gap is outside the window.

-- -------------------------------------------------------------------------
-- E) Three-visit chain A→B→C (HVAC, FC-005)
-- -------------------------------------------------------------------------
INSERT INTO work_order (id, tenant_id, title, description, state, priority,
                        asset_id, fault_code, created_at, updated_at, version)
VALUES
    ('80000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000001',
     'Quality E1 (chain A)', '', 'CLOSED', 'HIGH',
     '20000000-0000-0000-0000-000000000001', 'FC-005',
     now() - INTERVAL '80 days', now() - INTERVAL '80 days', 0),
    ('80000000-0000-0000-0000-000000000402', '00000000-0000-0000-0000-000000000001',
     'Quality E2 (chain B)', '', 'CLOSED', 'HIGH',
     '20000000-0000-0000-0000-000000000001', 'FC-005',
     now() - INTERVAL '65 days', now() - INTERVAL '65 days', 0),
    ('80000000-0000-0000-0000-000000000403', '00000000-0000-0000-0000-000000000001',
     'Quality E3 (chain C)', '', 'CLOSED', 'HIGH',
     '20000000-0000-0000-0000-000000000001', 'FC-005',
     now() - INTERVAL '50 days', now() - INTERVAL '50 days', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, fault_key, is_first_time_fix,
     maturity, closed_at, matured_at, created_at, updated_at)
VALUES
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000401',
     '20000000-0000-0000-0000-000000000001', 'FC-005', FALSE,
     'MATURED', now() - INTERVAL '80 days', now() - INTERVAL '50 days', now(), now()),
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000402',
     '20000000-0000-0000-0000-000000000001', 'FC-005', FALSE,
     'MATURED', now() - INTERVAL '65 days', now() - INTERVAL '35 days', now(), now()),
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000403',
     '20000000-0000-0000-0000-000000000001', 'FC-005', FALSE,
     'MATURED', now() - INTERVAL '50 days', now() - INTERVAL '20 days', now(), now())
ON CONFLICT (work_order_id) DO NOTHING;

INSERT INTO repeat_visit_link
    (id, earlier_work_order_id, later_work_order_id, asset_id, fault_key, days_between, linked_at)
VALUES
    (gen_random_uuid(),
     '80000000-0000-0000-0000-000000000401',
     '80000000-0000-0000-0000-000000000402',
     '20000000-0000-0000-0000-000000000001', 'FC-005', 15, now()),
    (gen_random_uuid(),
     '80000000-0000-0000-0000-000000000402',
     '80000000-0000-0000-0000-000000000403',
     '20000000-0000-0000-0000-000000000001', 'FC-005', 15, now())
ON CONFLICT (earlier_work_order_id, later_work_order_id) DO NOTHING;

-- -------------------------------------------------------------------------
-- F) UNCLASSIFIABLE work orders (missing asset or fault)
-- -------------------------------------------------------------------------
INSERT INTO work_order (id, tenant_id, title, description, state, priority,
                        asset_id, fault_code, created_at, updated_at, version)
VALUES
    -- Missing fault identity
    ('80000000-0000-0000-0000-000000000501', '00000000-0000-0000-0000-000000000001',
     'Quality F1 (no fault)', '', 'CLOSED', 'LOW',
     '20000000-0000-0000-0000-000000000001', NULL,
     now() - INTERVAL '50 days', now() - INTERVAL '50 days', 0),
    -- Missing asset identity
    ('80000000-0000-0000-0000-000000000502', '00000000-0000-0000-0000-000000000001',
     'Quality F2 (no asset)', '', 'CLOSED', 'LOW',
     NULL, 'FC-999',
     now() - INTERVAL '50 days', now() - INTERVAL '50 days', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, fault_key, is_first_time_fix,
     maturity, closed_at, matured_at, created_at, updated_at)
VALUES
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000501',
     '20000000-0000-0000-0000-000000000001', NULL, FALSE,
     'MATURED', now() - INTERVAL '50 days', now() - INTERVAL '20 days', now(), now()),
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000502',
     NULL, NULL, FALSE,
     'MATURED', now() - INTERVAL '50 days', now() - INTERVAL '20 days', now(), now())
ON CONFLICT (work_order_id) DO NOTHING;

-- -------------------------------------------------------------------------
-- G) PROVISIONAL rows (closed < 30 days ago, window still open)
-- -------------------------------------------------------------------------
INSERT INTO work_order (id, tenant_id, title, description, state, priority,
                        asset_id, fault_code, created_at, updated_at, version)
VALUES
    ('80000000-0000-0000-0000-000000000601', '00000000-0000-0000-0000-000000000001',
     'Quality G1 (provisional)', '', 'CLOSED', 'MEDIUM',
     '20000000-0000-0000-0000-000000000001', 'FC-006',
     now() - INTERVAL '5 days', now() - INTERVAL '5 days', 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO analytics_closure_projection
    (id, work_order_id, asset_id, fault_key, is_first_time_fix,
     maturity, closed_at, matured_at, created_at, updated_at)
VALUES
    (gen_random_uuid(), '80000000-0000-0000-0000-000000000601',
     '20000000-0000-0000-0000-000000000001', 'FC-006', TRUE,
     'PROVISIONAL', now() - INTERVAL '5 days', now() + INTERVAL '25 days', now(), now())
ON CONFLICT (work_order_id) DO NOTHING;
