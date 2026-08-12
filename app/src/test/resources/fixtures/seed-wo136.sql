-- seed-wo136.sql
-- Fixture for RecommendationControllerIT (WO-136).
-- UUID prefix: 00000000-0000-7136-8000-XXXXXXXXXXXX
--
-- Creates:
--   - One work order in NEW state (WO_NEW_ID)    → should get 200 recommendations
--   - One work order in ASSIGNED state            → should get 422
-- Depends on seed-core.sql for site 00000000-0000-7013-8000-000000000001.

-- Work order in NEW state (recommendations allowed)
INSERT INTO work_order (id, reference, state, priority, site_id, fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7136-8000-000000000001',
    'WO136-NEW',
    'NEW',
    'HIGH',
    '00000000-0000-7013-8000-000000000001',
    'ELECTRICAL',
    now(),
    ''
) ON CONFLICT (id) DO NOTHING;

-- Work order in ASSIGNED state (recommendations should return 422)
INSERT INTO work_order (id, reference, state, priority, site_id, assigned_technician_id, fault_category, created_at, fault_signature_tokens)
VALUES (
    '00000000-0000-7136-8000-000000000002',
    'WO136-ASSIGNED',
    'ASSIGNED',
    'MEDIUM',
    '00000000-0000-7013-8000-000000000001',
    '00000000-0000-7136-8000-000000000010',
    'MECHANICAL',
    now(),
    ''
) ON CONFLICT (id) DO NOTHING;
