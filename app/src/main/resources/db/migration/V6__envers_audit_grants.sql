-- =============================================================================
-- V6: Least-privilege grants on audit objects
-- =============================================================================
-- The runtime database role (fieldservice) receives SELECT and INSERT on all
-- audit tables and REVINFO. UPDATE and DELETE are explicitly withheld so that
-- immutability is enforced at the database level, not by convention.
--
-- A separate DDL-owner role (not the application role) holds CREATE/ALTER/DROP.
-- This migration is idempotent: it creates the role if absent and re-applies
-- grants on every run without error.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') THEN
        CREATE ROLE fieldservice;
    END IF;
END
$$;

-- Revoke any previously granted UPDATE or DELETE before applying the allow-list
REVOKE UPDATE, DELETE ON TABLE
    revinfo,
    work_order_aud,
    app_user_aud,
    site_aud,
    assignment_aud,
    technician_certification_aud,
    sla_policy_aud
    FROM fieldservice;

-- Grant only SELECT and INSERT — no UPDATE, no DELETE
GRANT SELECT, INSERT ON TABLE
    revinfo,
    work_order_aud,
    app_user_aud,
    site_aud,
    assignment_aud,
    technician_certification_aud,
    sla_policy_aud
    TO fieldservice;

-- Grant USAGE on the revision sequence (INSERT into revinfo needs nextval)
GRANT USAGE ON SEQUENCE revinfo_seq TO fieldservice;
