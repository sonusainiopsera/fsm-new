-- =============================================================================
-- V10: Least-privilege grants on identity audit objects.
-- Follows V6 pattern: SELECT and INSERT only; UPDATE, DELETE and TRUNCATE
-- are deliberately withheld — audit immutability enforced at the DB layer.
-- =============================================================================

-- role_assignment_aud: immutable audit history of role grants and revocations
GRANT SELECT, INSERT ON role_assignment_aud TO fieldservice_runtime;
