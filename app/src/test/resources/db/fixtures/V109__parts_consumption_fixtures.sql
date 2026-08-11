-- =============================================================================
-- V109: Parts consumption test fixtures (WO-149)
-- =============================================================================
-- Extends V100 inventory fixtures with:
--   - A work order in IN_PROGRESS state assigned to TECH_1 (TECH_1 Van A)
--   - Low-stock scenario: part PN-003 at Tech 1 Van A = exactly 2 units
--   - An already-depleted balance (quantity 0) for PN-004 for insufficient-stock tests
-- =============================================================================
-- Key IDs from V100:
--   TECH_1 user:       aaaaaaaa-0000-0000-0000-000000000011
--   TECH_1 tech:       00000000-0000-0000-0000-000000000011
--   TECH_1 Van A:      60000000-0000-0000-0000-000000000011
--   ACCT_A:            00000000-0000-0000-0000-000000000001
--   site_a1:           10000000-0000-0000-0000-000000000001
--   part PN-002:       50000000-0000-0000-0000-000000000002  (qty=4 at Van A)
--   part PN-003:       50000000-0000-0000-0000-000000000003  (qty=2 at Van A — low stock)
--   part PN-004:       50000000-0000-0000-0000-000000000004  (qty=0 at Van A — insufficient)
-- =============================================================================

-- A dedicated IN_PROGRESS work order for TECH_1 parts-consumption tests
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id, state, priority, description, version)
VALUES (
    '31000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    'IN_PROGRESS', 'HIGH',
    'Parts consumption test work order — TECH_1 in progress', 0
) ON CONFLICT DO NOTHING;

-- role_assignment so TECH_1 can access the new work order (mirrors V100 pattern)
INSERT INTO role_assignment (id, user_id, role, customer_account_id, technician_id, is_active, version)
VALUES (
    'cccc0000-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000011',
    'ROLE_TECHNICIAN',
    NULL,
    '00000000-0000-0000-0000-000000000011',
    true, 0
) ON CONFLICT DO NOTHING;

-- A COMPLETED work order for TECH_1 (should refuse consumption with 409)
INSERT INTO work_order (id, site_id, customer_id, assigned_technician_id, state, priority, description, version)
VALUES (
    '31000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000011',
    'COMPLETED', 'MEDIUM',
    'Completed work order — consumption must be refused', 0
) ON CONFLICT DO NOTHING;
