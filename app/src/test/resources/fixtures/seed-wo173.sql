-- WO-173 test fixtures: CSAT survey issuance and response capture.
--
-- Depends on:  seed-core.sql (sites, customers, portal_account_user rows)
--              seed-wo172.sql (closed work orders used as FK targets)
--
-- UUID namespacing:
--   Surveys:      00000000-0000-7173-8000-XXXXXXXXXXXX
--   Responses:    00000000-0000-7173-8000-0000000000XX (two-digit suffix ≥ 10)
--   Source events:00000000-0000-7173-9000-XXXXXXXXXXXX
--
-- Work orders referenced (from seed-wo172.sql, all CLOSED):
--   WO172-014  00000000-0000-7172-8000-000000000014  Acme Site 1 → Acme customer
--   WO172-015  00000000-0000-7172-8000-000000000015  Acme Site 1 → Acme customer
--   WO172-031  00000000-0000-7172-8000-000000000031  Acme Site 1 → Acme customer
--   WO172-068  00000000-0000-7172-8000-000000000068  Blue Site 1 → Blue customer

-- ============================================================
-- csat_survey rows
-- ============================================================
INSERT INTO csat_survey (id, work_order_id, account_id, source_event_id,
                          issued_at, expires_at, status, delivery_status, version)
VALUES
-- PENDING survey for Acme (happy-path submit target)
('00000000-0000-7173-8000-000000000001',
 '00000000-0000-7172-8000-000000000014',
 '00000000-0000-7012-8000-000000000001',
 '00000000-0000-7173-9000-000000000001',
 NOW() - INTERVAL '1 day', NOW() + INTERVAL '6 days',
 'PENDING', 'IN_APP', 0),

-- ANSWERED survey for Acme (triggers 409 on submit)
('00000000-0000-7173-8000-000000000002',
 '00000000-0000-7172-8000-000000000015',
 '00000000-0000-7012-8000-000000000001',
 '00000000-0000-7173-9000-000000000002',
 NOW() - INTERVAL '2 days', NOW() + INTERVAL '5 days',
 'ANSWERED', 'IN_APP', 1),

-- EXPIRED survey for Acme (triggers 422 on submit)
('00000000-0000-7173-8000-000000000003',
 '00000000-0000-7172-8000-000000000031',
 '00000000-0000-7012-8000-000000000001',
 '00000000-0000-7173-9000-000000000003',
 NOW() - INTERVAL '10 days', NOW() - INTERVAL '3 days',
 'EXPIRED', 'IN_APP', 1),

-- PENDING survey for Bluestone (triggers 404 when Acme user accesses it)
('00000000-0000-7173-8000-000000000004',
 '00000000-0000-7172-8000-000000000068',
 '00000000-0000-7012-8000-000000000002',
 '00000000-0000-7173-9000-000000000004',
 NOW() - INTERVAL '1 day', NOW() + INTERVAL '6 days',
 'PENDING', 'IN_APP', 0);

-- ============================================================
-- csat_response for the ANSWERED survey
-- comment = NULL to avoid encryption key dependency in fixtures
-- ============================================================
INSERT INTO csat_response (id, survey_id, score, nps_score, comment, submitted_at, version)
VALUES
('00000000-0000-7173-8000-000000000011',
 '00000000-0000-7173-8000-000000000002',
 4, 8, NULL, NOW() - INTERVAL '1 day', 0);
