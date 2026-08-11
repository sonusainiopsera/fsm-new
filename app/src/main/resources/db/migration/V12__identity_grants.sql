-- =============================================================================
-- V12: Least-privilege grants on identity audit objects
-- =============================================================================
-- The runtime database role (fieldservice) receives SELECT and INSERT on
-- role_assignment_aud.  UPDATE, DELETE and TRUNCATE are explicitly withheld
-- so that audit immutability is enforced at the database level, not by
-- convention.
--
-- The fieldservice role was created in V6; this script is idempotent.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') THEN
        CREATE ROLE fieldservice;
    END IF;
END
$$;

-- Revoke any UPDATE/DELETE that may have been granted by a prior run or error
REVOKE UPDATE, DELETE ON TABLE
    role_assignment_aud
    FROM fieldservice;

-- Grant only SELECT and INSERT on the new identity audit table
GRANT SELECT, INSERT ON TABLE
    role_assignment_aud
    TO fieldservice;
