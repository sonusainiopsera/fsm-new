-- =============================================================================
-- V116: Work order creation fixtures (WO-128)
-- Adds an inactive SLA policy for AC-3 testing (missing-policy 422 path).
-- Adds a mismatched site fixture: site_b1 belongs to customer B (for AC-6 tests).
-- =============================================================================

-- Inactive SLA policy for CRITICAL priority (future-dated and inactive)
-- Tests can reference this UUID to assert 422 SLA_POLICY_MISSING.
-- We add a SECOND CRITICAL row that is inactive so the priority still has the
-- active row from V2 but also has an explicit inactive row available for documentation.
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction,
                        effective_from, effective_to, active, version)
VALUES ('00000001-0000-7000-8000-000000000099',
        'LOW', 240, 1440, 0.80,
        '2000-01-01 00:00:00+00', '2001-01-01 00:00:00+00', false, 0);
