-- V102__work_order_5000_rows_fixture.sql
-- Deterministic 5000-row work-order fixture for pagination tests.
--
-- Intentionally generates duplicate created_at values in ~100-row buckets so that
-- the id tie-break is genuinely exercised in both offset and keyset traversal.
--
-- All work orders reference Customer Account A (ACCT_A) and Site A1 so that a
-- DISPATCHER scope (permit-all) sees all rows, while a TECHNICIAN scope sees only
-- their assigned subset.
--
-- UUIDs are generated deterministically from the row number so tests can reference
-- specific rows by index without an additional lookup.

INSERT INTO work_order (
    id,
    site_id,
    customer_id,
    asset_id,
    assigned_technician_id,
    state,
    priority,
    title,
    description,
    created_at,
    updated_at,
    version
)
SELECT
    -- Deterministic UUID from row number: prefix 50000000 to avoid collision with V100 fixtures
    ('50000000-0000-0000-' || lpad(to_hex(i / 100), 4, '0') || '-' || lpad(to_hex(i), 12, '0'))::uuid,

    -- All on Site A1 (Customer Account A)
    '10000000-0000-0000-0000-000000000001'::uuid,

    -- Customer Account A
    '00000000-0000-0000-0000-000000000001'::uuid,

    -- No asset
    NULL,

    -- Alternate between Tech 1 and unassigned, creating distinct scope buckets
    CASE WHEN i % 5 = 0 THEN '00000000-0000-0000-0000-000000000011'::uuid ELSE NULL END,

    -- Cycle through states
    (ARRAY['NEW','ASSIGNED','IN_PROGRESS','ON_HOLD','COMPLETED'])[((i - 1) % 5) + 1],

    -- Cycle through priorities
    (ARRAY['LOW','MEDIUM','HIGH','CRITICAL'])[((i - 1) % 4) + 1],

    -- Title with row number
    'Pagination Test Work Order ' || i,

    -- Description
    'Auto-generated fixture row ' || i || ' for pagination test coverage.',

    -- Duplicate created_at in buckets of 100 — exercises the id tie-break
    (TIMESTAMP '2024-01-01 00:00:00' + (((i - 1) / 100) * interval '1 minute')),

    -- updated_at same as created_at for simplicity
    (TIMESTAMP '2024-01-01 00:00:00' + (((i - 1) / 100) * interval '1 minute')),

    -- version
    0

FROM generate_series(1, 5000) AS s(i);
