-- ai-interaction-seed.sql
-- Synthetic fixture data for AiInteractionPrivacyTest and integration tests (WO-180).
--
-- All personal data is entirely synthetic — no real names, addresses, or contact details.
-- Uses entirely fictional identifiers to satisfy the compliance requirement that test
-- fixtures never contain real PII.
--
-- UUID prefix convention:
--   00000000-0000-7180-8000-XXXXXXXXXXXX  WO-180 aiaudit fixtures
--
-- Actor/user IDs reuse TestJwtFactory constants:
--   dddddddd-0000-0000-0000-000000000001  TECH_ONE_USER_ID
--   dddddddd-0000-0000-0000-000000000002  TECH_TWO_USER_ID
--   dddddddd-0000-0000-0000-000000000011  ADMIN_USER_ID

-- ── PII-seeded interaction (used by privacy test to assert no literals escape) ────────────
-- The redacted_prompt contains ONLY redacted tokens — no PII literals should appear.
-- This row is the privacy test anchor: every column is scanned for PII markers.
INSERT INTO ai_interaction (
    id, interaction_type, actor_user_id, work_order_id, provider, model,
    created_at, latency_ms, outcome,
    prompt_tokens, completion_tokens, estimated_cost,
    redaction_summary, redactor_version,
    redacted_prompt, response_text, response_truncated,
    grounding_basis, classification, retain_until
) VALUES (
    '00000000-0000-7180-8000-000000000001',
    'COPILOT_QUESTION',
    'dddddddd-0000-0000-0000-000000000001',
    '00000000-0000-7178-8000-000000000032',
    'test-provider', 'test-model-v1',
    NOW() - INTERVAL '1 hour', 1200, 'COMPLETED',
    45, 120, 0.000240,
    '{"CUSTOMER_NAME":1,"EMAIL":1,"PHONE":1,"ADDRESS":1,"POSTCODE":1}'::jsonb,
    '1.0',
    -- Redacted prompt: all PII replaced with category tokens
    'Boiler fault at [SITE_NAME]. Asset [ASSET_TAG] serial [SERIAL]. Contact [CONTACT_NAME] at [EMAIL_REDACTED] or [PHONE_REDACTED]. Site address [ADDRESS_REDACTED] [POSTCODE_REDACTED]. Fault: ignition failure on cold start.',
    -- Response: generic guidance — no PII
    'Check the ignition electrode gap. Verify gas valve operation. Inspect heat exchanger for scale buildup.',
    FALSE,
    '{"basis":[{"type":"asset","id":"00000000-0000-7178-8000-000000000021"},{"type":"work_order","id":"00000000-0000-7178-8000-000000000032"}]}'::jsonb,
    'CONFIDENTIAL',
    NOW() + INTERVAL '365 days'
) ON CONFLICT (id) DO NOTHING;

-- ── REFUSED_NO_GROUNDING interaction ────────────────────────────────────────────────────
INSERT INTO ai_interaction (
    id, interaction_type, actor_user_id, work_order_id, provider, model,
    created_at, latency_ms, outcome,
    prompt_tokens, completion_tokens, estimated_cost,
    redaction_summary, redactor_version,
    redacted_prompt, response_text, response_truncated,
    grounding_basis, classification, retain_until
) VALUES (
    '00000000-0000-7180-8000-000000000002',
    'COPILOT_QUESTION',
    'dddddddd-0000-0000-0000-000000000001',
    '00000000-0000-7178-8000-000000000031',
    NULL, NULL,
    NOW() - INTERVAL '2 hours', 50, 'REFUSED_NO_GROUNDING',
    0, 0, NULL,
    '{}'::jsonb, '1.0',
    NULL, NULL, FALSE,
    NULL, 'CONFIDENTIAL',
    NOW() + INTERVAL '365 days'
) ON CONFLICT (id) DO NOTHING;

-- ── DEGRADED interaction ────────────────────────────────────────────────────────────────
INSERT INTO ai_interaction (
    id, interaction_type, actor_user_id, work_order_id, provider, model,
    created_at, latency_ms, outcome,
    prompt_tokens, completion_tokens, estimated_cost,
    redaction_summary, redactor_version,
    redacted_prompt, response_text, response_truncated,
    grounding_basis, classification, retain_until
) VALUES (
    '00000000-0000-7180-8000-000000000003',
    'COPILOT_QUESTION',
    'dddddddd-0000-0000-0000-000000000002',
    NULL,
    'test-provider', 'test-model-v1',
    NOW() - INTERVAL '3 hours', 10050, 'DEGRADED',
    20, 0, NULL,
    '{"CUSTOMER_NAME":0}'::jsonb, '1.0',
    'Describe fault with pump unit at [SITE_NAME].',
    NULL, FALSE,
    NULL, 'CONFIDENTIAL',
    NOW() + INTERVAL '365 days'
) ON CONFLICT (id) DO NOTHING;

-- ── PHOTO_CAPTION interaction ────────────────────────────────────────────────────────────
INSERT INTO ai_interaction (
    id, interaction_type, actor_user_id, work_order_id, provider, model,
    created_at, latency_ms, outcome,
    prompt_tokens, completion_tokens, estimated_cost,
    redaction_summary, redactor_version,
    redacted_prompt, response_text, response_truncated,
    grounding_basis, classification, retain_until
) VALUES (
    '00000000-0000-7180-8000-000000000004',
    'PHOTO_CAPTION',
    'dddddddd-0000-0000-0000-000000000001',
    '00000000-0000-7178-8000-000000000032',
    'test-provider', 'test-vision-v1',
    NOW() - INTERVAL '4 hours', 2300, 'COMPLETED',
    10, 80, 0.000180,
    '{}'::jsonb, '1.0',
    NULL,
    'The photograph shows a gas boiler with visible corrosion on the heat exchanger flange. No personal information visible.',
    FALSE,
    '{"basis":[{"type":"asset","id":"00000000-0000-7178-8000-000000000021"}]}'::jsonb,
    'CONFIDENTIAL',
    NOW() + INTERVAL '90 days'
) ON CONFLICT (id) DO NOTHING;

-- ── Expired interaction (past retain_until — should be purged) ───────────────────────────
INSERT INTO ai_interaction (
    id, interaction_type, actor_user_id, work_order_id, provider, model,
    created_at, latency_ms, outcome,
    prompt_tokens, completion_tokens, estimated_cost,
    redaction_summary, redactor_version,
    redacted_prompt, response_text, response_truncated,
    grounding_basis, classification, retain_until
) VALUES (
    '00000000-0000-7180-8000-000000000099',
    'COPILOT_QUESTION',
    'dddddddd-0000-0000-0000-000000000001',
    NULL,
    NULL, NULL,
    NOW() - INTERVAL '400 days', 500, 'COMPLETED',
    10, 50, 0.000100,
    '{}'::jsonb, '1.0',
    'Old question text (redacted).',
    'Old response (generic).',
    FALSE,
    NULL, 'CONFIDENTIAL',
    NOW() - INTERVAL '35 days'    -- already past retain_until
) ON CONFLICT (id) DO NOTHING;

-- ── Rating for the first (COMPLETED) interaction ─────────────────────────────────────────
INSERT INTO ai_interaction_rating (id, ai_interaction_id, rating, rated_by, rated_at)
VALUES (
    '00000000-0000-7180-8001-000000000001',
    '00000000-0000-7180-8000-000000000001',
    'HELPFUL',
    'dddddddd-0000-0000-0000-000000000001',
    NOW() - INTERVAL '30 minutes'
) ON CONFLICT (id) DO NOTHING;

-- ── Rating for the PHOTO_CAPTION interaction (NOT_HELPFUL) ───────────────────────────────
INSERT INTO ai_interaction_rating (id, ai_interaction_id, rating, rated_by, rated_at)
VALUES (
    '00000000-0000-7180-8001-000000000002',
    '00000000-0000-7180-8000-000000000004',
    'NOT_HELPFUL',
    'dddddddd-0000-0000-0000-000000000001',
    NOW() - INTERVAL '2 hours'
) ON CONFLICT (id) DO NOTHING;
