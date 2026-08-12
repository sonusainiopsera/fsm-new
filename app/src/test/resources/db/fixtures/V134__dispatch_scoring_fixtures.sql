-- =============================================================================
-- V134: Dispatch scoring test fixtures
-- =============================================================================
-- Provides a controlled candidate fixture for ScoringWeightsIT.
-- Expected ranking documented inline.
--
-- Weights loaded from V52 seed rows (idempotent — no-conflict insert).
-- Scoring fixture creates 3 technicians with controlled attributes:
--
--   TECH_SCORE_A (id: e0000000-0001-...):
--     - Holds required cert ELEC_LV
--     - 5 prior jobs
--     - 30 min travel
--     - booked=6 h (below mean=7)
--     - parts available
--     → Highest score: cert 100%, exp 50%, travel 75%, workload no-penalty, parts 100%
--
--   TECH_SCORE_B (id: e0000000-0002-...):
--     - Holds required cert ELEC_LV
--     - 2 prior jobs
--     - 60 min travel
--     - booked=7 h (at mean=7)
--     - parts available
--     → Mid score: cert 100%, exp 20%, travel 50%, workload no-penalty, parts 100%
--
--   TECH_SCORE_C (id: e0000000-0003-...):
--     - Holds required cert ELEC_LV
--     - 8 prior jobs
--     - 90 min travel
--     - booked=9 h (above mean=7 → penalty)
--     - parts not available
--     → Lower score despite high experience: workload penalty + parts advisory penalty
--
-- Expected ranking: A > B > C
-- =============================================================================

-- Fixture technician users
INSERT INTO app_user (id, email, password_hash, display_name, is_active, version)
VALUES
  ('aaaaaaaa-0000-0000-0000-000000000091', 'score_tech_a@test.com', 'x', 'Score Tech A', true, 0),
  ('aaaaaaaa-0000-0000-0000-000000000092', 'score_tech_b@test.com', 'x', 'Score Tech B', true, 0),
  ('aaaaaaaa-0000-0000-0000-000000000093', 'score_tech_c@test.com', 'x', 'Score Tech C', true, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, employee_no, is_active, timezone, home_base_site_id, version)
SELECT
  'e0000000-0001-7000-8000-000000000001',
  'aaaaaaaa-0000-0000-0000-000000000091',
  'SCORE-001', true, 'UTC', s.id, 0
FROM site s LIMIT 1
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, employee_no, is_active, timezone, home_base_site_id, version)
SELECT
  'e0000000-0002-7000-8000-000000000002',
  'aaaaaaaa-0000-0000-0000-000000000092',
  'SCORE-002', true, 'UTC', s.id, 0
FROM site s LIMIT 1
ON CONFLICT (id) DO NOTHING;

INSERT INTO technician (id, user_id, employee_no, is_active, timezone, home_base_site_id, version)
SELECT
  'e0000000-0003-7000-8000-000000000003',
  'aaaaaaaa-0000-0000-0000-000000000093',
  'SCORE-003', true, 'UTC', s.id, 0
FROM site s LIMIT 1
ON CONFLICT (id) DO NOTHING;
