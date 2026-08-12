-- seed-wo138.sql
-- Fixture for AssignmentControllerIT (WO-138).
-- UUID prefix: 00000000-0000-7138-8000-XXXXXXXXXXXX
--
-- Creates:
--   - One work order in NEW state  (WO_NEW_ID)      → assignable
--   - One work order in ASSIGNED state (WO_ASSIGNED_ID) → not NEW, illegal transition
--   - One work order in CANCELLED state (WO_CANCELLED_ID) → illegal transition
-- Depends on seed-core.sql for site 00000000-0000-7013-8000-000000000001.
-- Technician IDs are defined in the test class itself for the stub eligibility service.

-- Work order in NEW state (eligible for assignment)
INSERT INTO work_order (id, reference, state, priority, site_id, fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7138-8000-000000000001',
    'WO138-NEW',
    'NEW',
    'HIGH',
    '00000000-0000-7013-8000-000000000001',
    'ELECTRICAL',
    now(),
    ''
) ON CONFLICT (id) DO NOTHING;

-- Work order already ASSIGNED (illegal transition test)
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7138-8000-000000000002',
    'WO138-ASSIGNED',
    'ASSIGNED',
    'MEDIUM',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7138-8000-000000000010',
    'MECHANICAL',
    now(),
    ''
) ON CONFLICT (id) DO NOTHING;

-- Work order in CANCELLED state (illegal transition test)
INSERT INTO work_order (id, reference, state, priority, site_id, fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7138-8000-000000000003',
    'WO138-CANCELLED',
    'CANCELLED',
    'LOW',
    '00000000-0000-7013-8000-000000000001',
    'MECHANICAL',
    now(),
    ''
) ON CONFLICT (id) DO NOTHING;
