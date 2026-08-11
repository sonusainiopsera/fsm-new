-- V128: Rectification and erasure test fixtures for WO-191
-- Anonymised subjects — no real personal data. UUIDs use dd-prefix.
--
-- Fixtures:
--   1. Customer with multiple revisions (for rectification test)
--   2. VERIFIED ERASURE DSAR request for that customer
--   3. VERIFIED RECTIFICATION DSAR request for that customer
--   4. Already-erased customer (for idempotency test)
--   5. ERASURE DSAR under legal hold (for legal-hold guard test)

-- ── Subject 1: rectifiable customer with PII-shaped contact data ─────────────
-- All values are IANA-reserved/fictional: no real data.
INSERT INTO customer (id, name, legal_name, primary_contact_name,
                      primary_contact_email, primary_contact_phone,
                      contact_email, contact_phone, billing_address,
                      is_active, version, created_at, updated_at)
VALUES
    ('dd000000-0000-0000-0000-000000000001',
     'Fixture Corp',
     'Fixture Corporation Ltd',
     'Test Person',
     'test.person@example.com',
     '+15555550400',
     'test.person@example.com',
     '+15555550400',
     '99 Fixture Road, Testville, TX 00099',
     true, 0, now(), now())
ON CONFLICT (id) DO NOTHING;

-- VERIFIED ERASURE DSAR for subject 1
INSERT INTO dsar_request (
    id, request_type, subject_type, subject_id,
    submitted_at, due_at, identity_verified_at, verification_method,
    state, attempt_count, version, created_at, updated_at
) VALUES (
    'dd000000-0000-7000-8000-000000000001',
    'ERASURE', 'CUSTOMER', 'dd000000-0000-0000-0000-000000000001',
    now() - interval '2 days', now() + interval '28 days',
    now() - interval '1 day', 'EXTERNAL_MANUAL',
    'VERIFIED', 0, 0, now() - interval '2 days', now() - interval '1 day'
) ON CONFLICT (id) DO NOTHING;

-- VERIFIED RECTIFICATION DSAR for subject 1
INSERT INTO dsar_request (
    id, request_type, subject_type, subject_id,
    submitted_at, due_at, identity_verified_at, verification_method,
    state, attempt_count, version, created_at, updated_at
) VALUES (
    'dd000000-0000-7000-8000-000000000002',
    'RECTIFICATION', 'CUSTOMER', 'dd000000-0000-0000-0000-000000000001',
    now() - interval '3 days', now() + interval '27 days',
    now() - interval '2 days', 'EXTERNAL_MANUAL',
    'VERIFIED', 0, 0, now() - interval '3 days', now() - interval '2 days'
) ON CONFLICT (id) DO NOTHING;

-- ── Subject 2: already-erased customer (for idempotency test) ────────────────
INSERT INTO customer (id, name, is_active, version, created_at, updated_at)
VALUES
    ('dd000000-0000-0000-0000-000000000002', 'Erased Fixture', true, 0, now(), now())
ON CONFLICT (id) DO NOTHING;

-- DSAR for subject 2
INSERT INTO dsar_request (
    id, request_type, subject_type, subject_id,
    submitted_at, due_at, identity_verified_at, verification_method,
    state, attempt_count, version, created_at, updated_at
) VALUES (
    'dd000000-0000-7000-8000-000000000003',
    'ERASURE', 'CUSTOMER', 'dd000000-0000-0000-0000-000000000002',
    now() - interval '10 days', now() + interval '20 days',
    now() - interval '9 days', 'EXTERNAL_MANUAL',
    'VERIFIED', 0, 0, now() - interval '10 days', now() - interval '9 days'
) ON CONFLICT (id) DO NOTHING;

-- Pre-existing tombstone for subject 2 (COMPLETED)
INSERT INTO subject_erasure (
    id, dsar_request_id, subject_type, subject_id,
    key_reference, erased_at, actor,
    erased_sections, verification_result, outcome,
    version, created_at, updated_at
) VALUES (
    'dd000000-0000-7000-8000-000000000010',
    'dd000000-0000-7000-8000-000000000003',
    'CUSTOMER', 'dd000000-0000-0000-0000-000000000002',
    'CUSTOMER:dd000000-0000-0000-0000-000000000002:v1',
    now() - interval '5 days', 'fixture-actor',
    '[]',
    '[]',
    'COMPLETED',
    0, now() - interval '5 days', now() - interval '5 days'
) ON CONFLICT (id) DO NOTHING;

-- ── Subject 3: UNVERIFIED DSAR (guard test) ───────────────────────────────────
INSERT INTO customer (id, name, is_active, version, created_at, updated_at)
VALUES
    ('dd000000-0000-0000-0000-000000000003', 'Unverified Fixture', true, 0, now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO dsar_request (
    id, request_type, subject_type, subject_id,
    submitted_at, due_at, state, attempt_count, version, created_at, updated_at
) VALUES (
    'dd000000-0000-7000-8000-000000000004',
    'ERASURE', 'CUSTOMER', 'dd000000-0000-0000-0000-000000000003',
    now(), now() + interval '30 days',
    'RECEIVED', 0, 0, now(), now()
) ON CONFLICT (id) DO NOTHING;
