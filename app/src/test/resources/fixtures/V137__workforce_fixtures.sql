-- V137: Workforce KPI fixture data (WO-163 AC-10)
--
-- Anonymized cohort: no names, emails, or contact details.
-- Covers 12 ISO weeks ending 2026-08-09 (week 33).
-- Technician IDs are deterministic UUIDs: no PII (BR-23).
--
-- Cohort:
--   TECH_A = fully utilized (high field time every week)
--   TECH_B = under-utilized (low field time, all weeks present)
--   TECH_C = onboarded mid-window (week 29 of 12-week window = week 22+)
--   TECH_D = deactivated mid-window (last active week 30)
--   TECH_E = zero logged time (appears in assignment but no labour records)
-- =============================================================================

-- Technicians (no name/contact, user_id references fixtures only)
INSERT INTO app_user (id, email, role, created_at, updated_at) VALUES
    ('aa000000-0000-0000-0000-000000000001', 'tech-a-anon@fixture.test', 'TECHNICIAN', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('bb000000-0000-0000-0000-000000000002', 'tech-b-anon@fixture.test', 'TECHNICIAN', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('cc000000-0000-0000-0000-000000000003', 'tech-c-anon@fixture.test', 'TECHNICIAN', '2026-05-15T00:00:00Z', '2026-05-15T00:00:00Z'),
    ('dd000000-0000-0000-0000-000000000004', 'tech-d-anon@fixture.test', 'TECHNICIAN', '2025-01-01T00:00:00Z', '2026-07-20T00:00:00Z'),
    ('ee000000-0000-0000-0000-000000000005', 'tech-e-anon@fixture.test', 'TECHNICIAN', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, active, created_at, updated_at) VALUES
    ('a1000000-0000-0000-0000-000000000001', 'aa000000-0000-0000-0000-000000000001', true,  '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('b2000000-0000-0000-0000-000000000002', 'bb000000-0000-0000-0000-000000000002', true,  '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z'),
    ('c3000000-0000-0000-0000-000000000003', 'cc000000-0000-0000-0000-000000000003', true,  '2026-05-15T00:00:00Z', '2026-05-15T00:00:00Z'),
    ('d4000000-0000-0000-0000-000000000004', 'dd000000-0000-0000-0000-000000000004', false, '2025-01-01T00:00:00Z', '2026-07-20T00:00:00Z'),
    ('e5000000-0000-0000-0000-000000000005', 'ee000000-0000-0000-0000-000000000005', true,  '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

-- Customer and site required by work_order FK
INSERT INTO customer (id, name, created_at, updated_at) VALUES
    ('f0000000-0000-0000-0000-000000000001', 'Fixture Customer', now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO site (id, customer_id, name, created_at, updated_at) VALUES
    ('f0000000-0000-0000-0000-000000000002', 'f0000000-0000-0000-0000-000000000001', 'Fixture Site', now(), now())
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- labour_time_record rows — one batch per technician per week
-- Window start = 2026-05-11 (week 20), end = 2026-08-09 (week 33)
-- ============================================================

-- TECH_A: fully utilized, ~2000 min/week across all 12 weeks
DO $$
DECLARE
    w date;
    wid uuid;
    ltid uuid;
BEGIN
    FOR week_offset IN 0..11 LOOP
        w := DATE '2026-05-11' + (week_offset * 7);

        -- Minimal WO for the FK
        wid := gen_random_uuid();
        INSERT INTO work_order (id, reference, customer_id, site_id, state, priority,
                                assigned_technician_id, created_at, updated_at)
        VALUES (wid, 'WO-FIXT-A-' || week_offset, 'f0000000-0000-0000-0000-000000000001',
                'f0000000-0000-0000-0000-000000000002',
                'COMPLETED', 'MEDIUM', 'a1000000-0000-0000-0000-000000000001',
                w + INTERVAL '8 hours', w + INTERVAL '16 hours');

        ltid := gen_random_uuid();
        INSERT INTO labour_time_record (id, work_order_id, technician_id, minutes, work_date, created_at)
        VALUES (ltid, wid, 'a1000000-0000-0000-0000-000000000001', 2000, w + INTERVAL '9 hours', now());
    END LOOP;
END $$;

-- TECH_B: under-utilized, ~600 min/week across all 12 weeks
DO $$
DECLARE
    w date;
    wid uuid;
    ltid uuid;
BEGIN
    FOR week_offset IN 0..11 LOOP
        w := DATE '2026-05-11' + (week_offset * 7);

        wid := gen_random_uuid();
        INSERT INTO work_order (id, reference, customer_id, site_id, state, priority,
                                assigned_technician_id, created_at, updated_at)
        VALUES (wid, 'WO-FIXT-B-' || week_offset, 'f0000000-0000-0000-0000-000000000001',
                'f0000000-0000-0000-0000-000000000002',
                'COMPLETED', 'LOW', 'b2000000-0000-0000-0000-000000000002',
                w + INTERVAL '8 hours', w + INTERVAL '11 hours');

        ltid := gen_random_uuid();
        INSERT INTO labour_time_record (id, work_order_id, technician_id, minutes, work_date, created_at)
        VALUES (ltid, wid, 'b2000000-0000-0000-0000-000000000002', 600, w + INTERVAL '9 hours', now());
    END LOOP;
END $$;

-- TECH_C: onboarded week 29 (2026-07-13), contributes only last 4 weeks
DO $$
DECLARE
    w date;
    wid uuid;
    ltid uuid;
BEGIN
    -- Only weeks 29-32 (onboarded 2026-07-13)
    FOR week_offset IN 9..11 LOOP
        w := DATE '2026-05-11' + (week_offset * 7);

        wid := gen_random_uuid();
        INSERT INTO work_order (id, reference, customer_id, site_id, state, priority,
                                assigned_technician_id, created_at, updated_at)
        VALUES (wid, 'WO-FIXT-C-' || week_offset, 'f0000000-0000-0000-0000-000000000001',
                'f0000000-0000-0000-0000-000000000002',
                'COMPLETED', 'MEDIUM', 'c3000000-0000-0000-0000-000000000003',
                w + INTERVAL '8 hours', w + INTERVAL '14 hours');

        ltid := gen_random_uuid();
        INSERT INTO labour_time_record (id, work_order_id, technician_id, minutes, work_date, created_at)
        VALUES (ltid, wid, 'c3000000-0000-0000-0000-000000000003', 1400, w + INTERVAL '9 hours', now());
    END LOOP;
END $$;

-- TECH_D: deactivated after week 30 (2026-07-27), contributes weeks 20-30
DO $$
DECLARE
    w date;
    wid uuid;
    ltid uuid;
BEGIN
    FOR week_offset IN 0..10 LOOP
        w := DATE '2026-05-11' + (week_offset * 7);

        wid := gen_random_uuid();
        INSERT INTO work_order (id, reference, customer_id, site_id, state, priority,
                                assigned_technician_id, created_at, updated_at)
        VALUES (wid, 'WO-FIXT-D-' || week_offset, 'f0000000-0000-0000-0000-000000000001',
                'f0000000-0000-0000-0000-000000000002',
                'COMPLETED', 'HIGH', 'd4000000-0000-0000-0000-000000000004',
                w + INTERVAL '8 hours', w + INTERVAL '15 hours');

        ltid := gen_random_uuid();
        INSERT INTO labour_time_record (id, work_order_id, technician_id, minutes, work_date, created_at)
        VALUES (ltid, wid, 'd4000000-0000-0000-0000-000000000004', 1800, w + INTERVAL '9 hours', now());
    END LOOP;
END $$;

-- TECH_E: zero logged time (never has labour_time_record rows) — no insert needed
