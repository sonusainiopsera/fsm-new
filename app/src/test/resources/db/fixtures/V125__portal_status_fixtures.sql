-- =============================================================================
-- V125: Portal status API fixtures for WO-171 integration tests.
-- =============================================================================
-- One work order per lifecycle state, all on Site A1 (ACCT_A), so that portal
-- user A (linked to ACCT_A) can access them and every CustomerStateLabels branch
-- is exercised (AC-10).
--
-- IDs use the e0000000 prefix range to avoid collision with earlier fixtures.
-- TECH_1 (00000000-...-0011) is reused as the assigned technician.
-- SLA deadlines are set to future values so no SLA risk sweep fires during tests.
--
-- Fixture topology:
--   e0000000-...-0001  NEW           (no technician)
--   e0000000-...-0002  ASSIGNED      (TECH_1)
--   e0000000-...-0003  EN_ROUTE      (TECH_1)
--   e0000000-...-0004  IN_PROGRESS   (TECH_1)
--   e0000000-...-0005  ON_HOLD       (TECH_1, + active work_order_hold record)
--   e0000000-...-0006  COMPLETED     (TECH_1)
--   e0000000-...-0007  CLOSED        (TECH_1)
--   e0000000-...-0008  CANCELLED     (no technician)
-- =============================================================================

INSERT INTO work_order
    (id, site_id, customer_id, assigned_technician_id, state, priority,
     description, reference, response_due_at, resolution_due_at, version)
VALUES
    -- NEW
    ('e0000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     NULL,
     'NEW', 'MEDIUM',
     'Portal status fixture — state NEW',
     'WO-STATUS-001',
     now() + INTERVAL '4 hours', now() + INTERVAL '48 hours', 0),

    -- ASSIGNED
    ('e0000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'ASSIGNED', 'MEDIUM',
     'Portal status fixture — state ASSIGNED',
     'WO-STATUS-002',
     now() + INTERVAL '4 hours', now() + INTERVAL '48 hours', 0),

    -- EN_ROUTE
    ('e0000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'EN_ROUTE', 'MEDIUM',
     'Portal status fixture — state EN_ROUTE',
     'WO-STATUS-003',
     now() + INTERVAL '4 hours', now() + INTERVAL '48 hours', 0),

    -- IN_PROGRESS
    ('e0000000-0000-0000-0000-000000000004',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'IN_PROGRESS', 'MEDIUM',
     'Portal status fixture — state IN_PROGRESS',
     'WO-STATUS-004',
     now() + INTERVAL '4 hours', now() + INTERVAL '48 hours', 0),

    -- ON_HOLD
    ('e0000000-0000-0000-0000-000000000005',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'ON_HOLD', 'MEDIUM',
     'Portal status fixture — state ON_HOLD',
     'WO-STATUS-005',
     now() + INTERVAL '4 hours', now() + INTERVAL '48 hours', 0),

    -- COMPLETED
    ('e0000000-0000-0000-0000-000000000006',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'COMPLETED', 'MEDIUM',
     'Portal status fixture — state COMPLETED',
     'WO-STATUS-006',
     now() + INTERVAL '4 hours', now() + INTERVAL '48 hours', 0),

    -- CLOSED
    ('e0000000-0000-0000-0000-000000000007',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000011',
     'CLOSED', 'MEDIUM',
     'Portal status fixture — state CLOSED',
     'WO-STATUS-007',
     now() + INTERVAL '4 hours', now() + INTERVAL '48 hours', 0),

    -- CANCELLED
    ('e0000000-0000-0000-0000-000000000008',
     '10000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000001',
     NULL,
     'CANCELLED', 'MEDIUM',
     'Portal status fixture — state CANCELLED',
     'WO-STATUS-008',
     now() + INTERVAL '4 hours', now() + INTERVAL '48 hours', 0)

ON CONFLICT (id) DO NOTHING;

-- Active hold record for ON_HOLD work order
-- Uses reason code AWAITING_PARTS (seeded in V19 migration)
INSERT INTO work_order_hold (id, work_order_id, reason_code, started_at)
VALUES
    ('e1000000-0000-0000-0000-000000000001',
     'e0000000-0000-0000-0000-000000000005',
     'AWAITING_PARTS',
     now() - INTERVAL '1 hour')
ON CONFLICT (id) DO NOTHING;
