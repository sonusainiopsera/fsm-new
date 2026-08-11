-- V120: DSAR test fixtures for WO-190
-- Anonymised subjects with no real personal data.
-- UUIDs use ee-prefix to avoid collisions with other fixture ranges.

-- Subject A: present in all modules (CUSTOMER type)
-- RECEIVED state — can test intake flow
INSERT INTO dsar_request (
    id, request_type, subject_type, subject_id,
    submitted_at, due_at, state,
    attempt_count, notes, version, created_at, updated_at
) VALUES (
    'ee000000-0000-7000-8000-000000000001',
    'ACCESS', 'CUSTOMER',
    '00000000-0000-0000-0000-000000000001', -- customer fixture from V100
    NOW(), NOW() + INTERVAL '30 days',
    'RECEIVED', 0,
    'Integration test fixture - customer with full data',
    0, NOW(), NOW()
) ON CONFLICT (id) DO NOTHING;

-- Subject B: VERIFIED state — ready for export assembly
INSERT INTO dsar_request (
    id, request_type, subject_type, subject_id,
    submitted_at, due_at,
    identity_verified_at, verification_method,
    state, attempt_count, version, created_at, updated_at
) VALUES (
    'ee000000-0000-7000-8000-000000000002',
    'PORTABILITY', 'CUSTOMER',
    '00000000-0000-0000-0000-000000000001',
    NOW() - INTERVAL '5 days', NOW() + INTERVAL '25 days',
    NOW() - INTERVAL '4 days', 'EXTERNAL_MANUAL',
    'VERIFIED', 0, 0, NOW() - INTERVAL '5 days', NOW() - INTERVAL '4 days'
) ON CONFLICT (id) DO NOTHING;

-- Subject C: already FULFILLED — export artifact present
INSERT INTO dsar_request (
    id, request_type, subject_type, subject_id,
    submitted_at, due_at,
    identity_verified_at, verification_method,
    state, outcome, attempt_count, version, created_at, updated_at
) VALUES (
    'ee000000-0000-7000-8000-000000000003',
    'ACCESS', 'CUSTOMER',
    '00000000-0000-0000-0000-000000000002', -- second customer, sparse data
    NOW() - INTERVAL '10 days', NOW() + INTERVAL '20 days',
    NOW() - INTERVAL '9 days', 'EMAIL_OTP',
    'FULFILLED', 'FULFILLED_IN_TIME', 1, 0,
    NOW() - INTERVAL '10 days', NOW() - INTERVAL '8 days'
) ON CONFLICT (id) DO NOTHING;

-- Export artifact for Subject C
INSERT INTO dsar_export_artifact (
    id, dsar_request_id, storage_key, manifest, export_data, byte_size,
    generated_at, version, created_at, updated_at
) VALUES (
    'ee000000-0000-7000-8000-000000000010',
    'ee000000-0000-7000-8000-000000000003',
    'dsar/ee000000-0000-7000-8000-000000000003/attempt-1.json',
    '[{"sectionName":"identity","sourceModule":"identity","rowCount":1}]',
    '{"dsarRequestId":"ee000000-0000-7000-8000-000000000003","sections":[]}',
    42,
    NOW() - INTERVAL '8 days',
    0, NOW() - INTERVAL '8 days', NOW() - INTERVAL '8 days'
) ON CONFLICT (id) DO NOTHING;

-- Subject D: REJECTED — test terminal state
INSERT INTO dsar_request (
    id, request_type, subject_type, subject_id,
    submitted_at, due_at, state, outcome, outcome_note,
    attempt_count, version, created_at, updated_at
) VALUES (
    'ee000000-0000-7000-8000-000000000004',
    'ERASURE', 'CUSTOMER',
    '00000000-0000-0000-0000-000000000001',
    NOW() - INTERVAL '15 days', NOW() + INTERVAL '15 days',
    'REJECTED', 'REJECTED', 'Could not verify identity within required window',
    0, 0, NOW() - INTERVAL '15 days', NOW() - INTERVAL '12 days'
) ON CONFLICT (id) DO NOTHING;
