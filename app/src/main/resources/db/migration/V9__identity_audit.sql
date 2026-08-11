-- =============================================================================
-- V9: Envers audit table for role_assignment.
-- Mirrors V5 convention: composite (id, rev) PK, all columns nullable,
-- FK to revinfo, wired to the existing revision sequence and REVINFO table.
-- role_assignment_aud intentionally omits no columns (no Restricted material).
-- =============================================================================

CREATE TABLE role_assignment_aud (
    id          UUID        NOT NULL,
    rev         BIGINT      NOT NULL,
    revtype     SMALLINT,
    user_id     UUID,
    role_name   VARCHAR(50),
    granted_at  TIMESTAMPTZ,
    granted_by  UUID,
    PRIMARY KEY (id, rev),
    FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX idx_role_assignment_aud_rev ON role_assignment_aud USING BRIN (rev);
