-- V10__identity_grants.sql
-- Least-privilege grants for the runtime database role on identity audit objects.
-- SELECT and INSERT only; UPDATE, DELETE and TRUNCATE are explicitly revoked so
-- immutability of the audit trail is enforced by the database, not by application code.
--
-- CREATE ROLE IF NOT EXISTS is idempotent on both PostgreSQL and H2.

CREATE ROLE IF NOT EXISTS fieldservice;

GRANT SELECT, INSERT ON role_assignment_aud TO fieldservice;

-- Explicit revoke is a no-op if the privilege was never granted, but makes the
-- intent unambiguous and prevents a superuser GRANT from silently enabling it.
REVOKE UPDATE, DELETE, TRUNCATE ON role_assignment_aud FROM PUBLIC;
