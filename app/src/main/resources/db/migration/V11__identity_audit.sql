-- =============================================================================
-- V11: Envers audit table for role_assignment
-- =============================================================================
-- Mirrors role_assignment columns for Hibernate Envers change tracking.
-- Wired to the existing REVINFO table and revinfo_seq from V5.
--
-- Password hashes and token hashes are NEVER audited (CONFIDENTIAL).
-- The external_subject column on app_user is @NotAudited (future PII field).
-- =============================================================================

CREATE TABLE IF NOT EXISTS role_assignment_aud (
    id          UUID        NOT NULL,
    rev         INTEGER     NOT NULL,
    revtype     SMALLINT    NOT NULL,
    user_id     UUID,
    role_name   VARCHAR(20),
    granted_at  TIMESTAMPTZ,
    granted_by  UUID,

    CONSTRAINT pk_role_assignment_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_role_assignment_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- BRIN index — append-only audit table naturally ordered by insertion (rev) time
CREATE INDEX IF NOT EXISTS idx_role_assignment_aud_rev_brin
    ON role_assignment_aud USING brin (rev);

COMMENT ON TABLE role_assignment_aud IS 'Envers append-only audit log; UPDATE and DELETE are withheld from the runtime role (see V12)';
