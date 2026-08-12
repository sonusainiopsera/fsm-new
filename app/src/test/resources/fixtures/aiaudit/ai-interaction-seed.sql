-- =============================================================================
-- AI interaction audit log test fixtures (WO-180)
-- All data is entirely synthetic — no real personal information.
-- IDs in ee000000-* range to avoid collisions with other fixtures.
--
-- Provides: all 6 outcome classes, rated and unrated interactions,
--           rows inside and outside the retention window.
-- =============================================================================

-- Actor users (synthetic UUIDs)
-- ee000000-0000-0000-0000-000000000011  technician T1
-- ee000000-0000-0000-0000-000000000012  technician T2
-- ee000000-0000-0000-0000-000000000099  admin

-- ── Inside retention window (retain_until in future) ──────────────────────────

INSERT INTO ai_interaction (
    id, actor_user_id, work_order_id, interaction_type,
    provider, model, created_at, latency_ms, outcome,
    prompt_tokens, completion_tokens, estimated_cost,
    redaction_summary, redactor_version,
    redacted_prompt, response_text, response_truncated,
    grounding_basis, classification, retain_until
) VALUES

-- COMPLETED, rated HELPFUL
('ee000000-0000-0000-0000-000000000001',
 'ee000000-0000-0000-0000-000000000011',
 'cc000000-0000-0000-0000-000000000030',
 'COPILOT_QUESTION', 'openai', 'gpt-4o-mini',
 NOW() - INTERVAL '1 hour', 1200, 'COMPLETED',
 120, 80, 0.00002000,
 '{"CUSTOMER_NAME":1,"PHONE":1}'::jsonb, 'v1',
 'What is the fault on work order [CUSTOMER_NAME_1]?',
 'The fault description indicates a pressure relief valve fault.',
 false,
 '{"assetId":"aa000000-0000-0000-0000-000000000001"}'::jsonb,
 'CONFIDENTIAL',
 NOW() + INTERVAL '364 days'),

-- COMPLETED, rated NOT_HELPFUL
('ee000000-0000-0000-0000-000000000002',
 'ee000000-0000-0000-0000-000000000011',
 'cc000000-0000-0000-0000-000000000030',
 'COPILOT_QUESTION', 'openai', 'gpt-4o-mini',
 NOW() - INTERVAL '2 hours', 980, 'COMPLETED',
 95, 60, 0.00001550,
 '{}'::jsonb, 'v1',
 'What was the last repair on this unit?',
 'No prior service records found.',
 false,
 '{"assetId":"aa000000-0000-0000-0000-000000000001"}'::jsonb,
 'CONFIDENTIAL',
 NOW() + INTERVAL '363 days'),

-- COMPLETED, unrated
('ee000000-0000-0000-0000-000000000003',
 'ee000000-0000-0000-0000-000000000012',
 NULL,
 'COPILOT_QUESTION', 'openai', 'gpt-4o-mini',
 NOW() - INTERVAL '3 hours', 1500, 'COMPLETED',
 110, 90, 0.00002000,
 '{"EMAIL":2}'::jsonb, 'v1',
 'Describe the standard maintenance procedure.',
 'Standard maintenance includes checking all seals and pressure readings.',
 false,
 NULL,
 'CONFIDENTIAL',
 NOW() + INTERVAL '362 days'),

-- REFUSED_NO_GROUNDING
('ee000000-0000-0000-0000-000000000004',
 'ee000000-0000-0000-0000-000000000011',
 'cc000000-0000-0000-0000-000000000031',
 'COPILOT_QUESTION', NULL, NULL,
 NOW() - INTERVAL '4 hours', NULL, 'REFUSED_NO_GROUNDING',
 0, 0, 0.00000000,
 '{}'::jsonb, 'v1',
 NULL, NULL, false, NULL, 'CONFIDENTIAL',
 NOW() + INTERVAL '361 days'),

-- DEGRADED
('ee000000-0000-0000-0000-000000000005',
 'ee000000-0000-0000-0000-000000000012',
 'cc000000-0000-0000-0000-000000000032',
 'COPILOT_QUESTION', 'openai', 'gpt-4o-mini',
 NOW() - INTERVAL '5 hours', 10001, 'DEGRADED',
 50, 0, 0.00000000,
 '{}'::jsonb, 'v1',
 NULL, NULL, false, NULL, 'CONFIDENTIAL',
 NOW() + INTERVAL '360 days'),

-- CANCELLED
('ee000000-0000-0000-0000-000000000006',
 'ee000000-0000-0000-0000-000000000011',
 'cc000000-0000-0000-0000-000000000030',
 'COPILOT_QUESTION', 'openai', 'gpt-4o-mini',
 NOW() - INTERVAL '6 hours', 2100, 'CANCELLED',
 80, 30, 0.00001100,
 '{}'::jsonb, 'v1',
 'Partially answered question',
 NULL, false, NULL, 'CONFIDENTIAL',
 NOW() + INTERVAL '359 days'),

-- CAPPED
('ee000000-0000-0000-0000-000000000007',
 'ee000000-0000-0000-0000-000000000012',
 NULL,
 'COPILOT_QUESTION', NULL, NULL,
 NOW() - INTERVAL '7 hours', NULL, 'CAPPED',
 0, 0, 0.00000000,
 '{}'::jsonb, 'v1',
 NULL, NULL, false, NULL, 'CONFIDENTIAL',
 NOW() + INTERVAL '358 days'),

-- ERROR
('ee000000-0000-0000-0000-000000000008',
 'ee000000-0000-0000-0000-000000000011',
 'cc000000-0000-0000-0000-000000000033',
 'COPILOT_QUESTION', 'openai', NULL,
 NOW() - INTERVAL '8 hours', 500, 'ERROR',
 0, 0, 0.00000000,
 '{}'::jsonb, 'v1',
 NULL, NULL, false, NULL, 'CONFIDENTIAL',
 NOW() + INTERVAL '357 days');

-- ── Outside retention window (retain_until in past) ────────────────────────────

INSERT INTO ai_interaction (
    id, actor_user_id, work_order_id, interaction_type,
    provider, model, created_at, latency_ms, outcome,
    prompt_tokens, completion_tokens, estimated_cost,
    redaction_summary, redactor_version,
    redacted_prompt, response_text, response_truncated,
    grounding_basis, classification, retain_until
) VALUES
('ee000000-0000-0000-0000-000000000009',
 'ee000000-0000-0000-0000-000000000011',
 NULL,
 'COPILOT_QUESTION', 'openai', 'gpt-4o-mini',
 NOW() - INTERVAL '400 days', 1000, 'COMPLETED',
 100, 70, 0.00001700,
 '{}'::jsonb, 'v1',
 'Old completed question',
 'Old answer',
 false, NULL, 'CONFIDENTIAL',
 NOW() - INTERVAL '1 day');

-- ── Ratings ─────────────────────────────────────────────────────────────────────

INSERT INTO ai_interaction_rating (id, ai_interaction_id, rating, rated_by, rated_at) VALUES
('ee000000-0000-0000-0001-000000000001',
 'ee000000-0000-0000-0000-000000000001',
 'HELPFUL',
 'ee000000-0000-0000-0000-000000000011',
 NOW() - INTERVAL '30 minutes'),

('ee000000-0000-0000-0001-000000000002',
 'ee000000-0000-0000-0000-000000000002',
 'NOT_HELPFUL',
 'ee000000-0000-0000-0000-000000000011',
 NOW() - INTERVAL '90 minutes');
