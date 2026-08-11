-- Bulk seed script for WO-127 performance baseline.
--
-- Generates ~100 000 work orders distributed across 5 customers, 20 sites,
-- 50 technicians, all 5 open states, all 4 priorities, and varying at_risk /
-- deadline values.  Run this script ONCE against a local or CI PostgreSQL
-- instance before running any latency benchmarks or EXPLAIN ANALYZE captures.
--
-- Prerequisites: V1–V19 Flyway migrations must already be applied.

-- =========================================================
-- Reference data  (customers → sites → users → technicians)
-- =========================================================

INSERT INTO customer (id, name, version)
SELECT
    ('b0000000-0000-0000-0000-' || LPAD(n::text, 12, '0'))::uuid,
    'BulkCorp-' || n,
    0
FROM generate_series(1, 5) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO site (id, name, customer_id, version)
SELECT
    ('b1000000-0000-0000-0000-' || LPAD(n::text, 12, '0'))::uuid,
    'BulkSite-' || n,
    ('b0000000-0000-0000-0000-' || LPAD(((n - 1) % 5 + 1)::text, 12, '0'))::uuid,
    0
FROM generate_series(1, 20) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_user (id, email, password_hash, full_name, active, version)
SELECT
    ('b2000000-0000-0000-0000-' || LPAD(n::text, 12, '0'))::uuid,
    'bulk-tech-' || n || '@bulk.test',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    'Bulk Tech ' || n,
    TRUE,
    0
FROM generate_series(1, 50) AS n
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, full_name, version)
SELECT
    ('b3000000-0000-0000-0000-' || LPAD(n::text, 12, '0'))::uuid,
    ('b2000000-0000-0000-0000-' || LPAD(n::text, 12, '0'))::uuid,
    'Bulk Tech ' || n,
    0
FROM generate_series(1, 50) AS n
ON CONFLICT (id) DO NOTHING;

-- =========================================================
-- 100 000 work orders
-- =========================================================
--
-- Distribution across:
--   state    : NEW / ASSIGNED / IN_PROGRESS / ON_HOLD / COMPLETED  (cycled)
--   priority : CRITICAL / HIGH / MEDIUM / LOW                       (cycled)
--   site     : cycles through 20 bulk sites
--   tech     : ASSIGNED/IN_PROGRESS/ON_HOLD rows get a technician (cycles 50);
--              NEW rows have no assigned technician
--   at_risk  : TRUE for roughly 1 in 10 rows
--   deadlines: spread over the next 90 days from 2026-08-11

INSERT INTO work_order
    (id, reference, state, priority, site_id, assigned_technician_id,
     at_risk, cumulative_hold_minutes, resolution_deadline, version)
SELECT
    gen_random_uuid(),
    'BULK-' || LPAD(n::text, 6, '0'),
    (ARRAY['NEW','ASSIGNED','IN_PROGRESS','ON_HOLD','COMPLETED'])[(n % 5) + 1],
    (ARRAY['CRITICAL','HIGH','MEDIUM','LOW'])[(n % 4) + 1],
    ('b1000000-0000-0000-0000-' || LPAD(((n % 20) + 1)::text, 12, '0'))::uuid,
    CASE WHEN (n % 5) = 0 THEN NULL
         ELSE ('b3000000-0000-0000-0000-' || LPAD(((n % 50) + 1)::text, 12, '0'))::uuid
    END,
    (n % 10 = 0),
    (n % 10) * 15,
    (TIMESTAMPTZ '2026-08-11 00:00:00 UTC') + (n % 90) * INTERVAL '1 day',
    0
FROM generate_series(1, 100000) AS n
ON CONFLICT DO NOTHING;
