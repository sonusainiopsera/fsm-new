-- =============================================================================
-- V6: Least-privilege grants on audit objects.
-- The fieldservice_runtime role receives SELECT and INSERT only.
-- UPDATE and DELETE are intentionally withheld — immutability is enforced
-- at the database layer, not by application convention.
-- The migration/DDL owner role retains full ownership privileges.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice_runtime') THEN
        CREATE ROLE fieldservice_runtime NOLOGIN;
    END IF;
END $$;

-- Sequence: runtime role may call nextval (needed to INSERT into revinfo)
GRANT USAGE, SELECT ON SEQUENCE revinfo_seq TO fieldservice_runtime;

-- REVINFO and all audit tables: SELECT + INSERT only; no UPDATE, no DELETE
GRANT SELECT, INSERT ON revinfo                       TO fieldservice_runtime;
GRANT SELECT, INSERT ON work_order_aud                TO fieldservice_runtime;
GRANT SELECT, INSERT ON assignment_aud                TO fieldservice_runtime;
GRANT SELECT, INSERT ON technician_certification_aud  TO fieldservice_runtime;
GRANT SELECT, INSERT ON app_user_aud                  TO fieldservice_runtime;
GRANT SELECT, INSERT ON site_aud                      TO fieldservice_runtime;
GRANT SELECT, INSERT ON sla_policy_aud                TO fieldservice_runtime;
