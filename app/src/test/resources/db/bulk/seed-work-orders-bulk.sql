-- seed-work-orders-bulk.sql
-- Generates 100 000 work orders across all states, priorities, customers, sites, and technicians.
-- Used by performance / p95-latency tests (WO-127 acceptance criterion 8).
--
-- Pre-requisites (must already exist in the DB before this script runs):
--   customer rows: 00000000-0000-0000-0000-000000000001 (ACCT_A)
--                  00000000-0000-0000-0000-000000000002 (ACCT_B)
--   site rows:     10000000-0000-0000-0000-000000000001..3
--   technician:    00000000-0000-0000-0000-000000000011 (TECH_1)
--                  00000000-0000-0000-0000-000000000012 (TECH_2)
--
-- This script is idempotent via ON CONFLICT DO NOTHING so re-runs are safe.
-- IDs are deterministic: e5000000-0000-0000-<NNNN>-<MMMMMMMM> where
--   NNNN = row number mod 10000  (upper 16 bits of the row band)
--   MMMMMMMM = row number as 8-hex digit lower 32 bits

INSERT INTO work_order
    (id, site_id, customer_id, assigned_technician_id, state, priority,
     description, version, cumulative_hold_minutes, sla_deadline)
SELECT
    -- Deterministic UUID v4-compatible value
    ('e5000000-0000-0000-' ||
        lpad(to_hex(n % 65536), 4, '0') || '-' ||
        lpad(to_hex(n), 12, '0')
    )::uuid                                                              AS id,

    -- Cycle through 3 sites
    CASE (n % 3)
        WHEN 0 THEN '10000000-0000-0000-0000-000000000001'::uuid
        WHEN 1 THEN '10000000-0000-0000-0000-000000000002'::uuid
        ELSE        '10000000-0000-0000-0000-000000000003'::uuid
    END                                                                  AS site_id,

    -- Sites 1+2 belong to ACCT_A; site 3 belongs to ACCT_B
    CASE (n % 3)
        WHEN 2 THEN '00000000-0000-0000-0000-000000000002'::uuid
        ELSE        '00000000-0000-0000-0000-000000000001'::uuid
    END                                                                  AS customer_id,

    -- Assign every other row to TECH_1 or TECH_2 (leave some unassigned)
    CASE (n % 5)
        WHEN 0 THEN '00000000-0000-0000-0000-000000000011'::uuid
        WHEN 1 THEN '00000000-0000-0000-0000-000000000012'::uuid
        ELSE NULL
    END                                                                  AS assigned_technician_id,

    -- Cycle through 6 states
    (ARRAY['NEW','ASSIGNED','IN_PROGRESS','ON_HOLD','COMPLETED','CANCELLED'])[(n % 6) + 1]::text
                                                                         AS state,

    -- Cycle through 3 priorities
    (ARRAY['HIGH','MEDIUM','LOW'])[(n % 3) + 1]::text                   AS priority,

    'Bulk seed work order #' || n                                        AS description,

    0                                                                    AS version,

    -- Roughly 30 % of rows have cumulative hold time
    CASE WHEN n % 3 = 0 THEN (n % 120) ELSE 0 END                       AS cumulative_hold_minutes,

    -- 10 % of rows have a past SLA deadline (at-risk candidates)
    CASE WHEN n % 10 = 0
         THEN (TIMESTAMP '2020-01-01 00:00:00' + (n % 365) * INTERVAL '1 day')
         ELSE NULL
    END                                                                  AS sla_deadline

FROM generate_series(1, 100000) AS n
ON CONFLICT (id) DO NOTHING;
