-- V6__audit_grants.sql
-- Least-privilege grants for the runtime database role on audit objects.
-- The runtime role receives SELECT and INSERT only.
-- UPDATE and DELETE are explicitly withheld — immutability is enforced by the database.
--
-- CREATE ROLE IF NOT EXISTS is idempotent on both PostgreSQL and H2.

CREATE ROLE IF NOT EXISTS fieldservice;

GRANT SELECT, INSERT ON REVINFO                      TO fieldservice;
GRANT SELECT, INSERT ON work_order_aud               TO fieldservice;
GRANT SELECT, INSERT ON assignment_aud               TO fieldservice;
GRANT SELECT, INSERT ON technician_certification_aud TO fieldservice;
GRANT SELECT, INSERT ON app_user_aud                 TO fieldservice;
GRANT SELECT, INSERT ON site_aud                     TO fieldservice;
GRANT SELECT, INSERT ON sla_policy_aud               TO fieldservice;
-- Sequence usage granted separately in production via DBA provisioning script;
-- omitted here for cross-database test compatibility.
