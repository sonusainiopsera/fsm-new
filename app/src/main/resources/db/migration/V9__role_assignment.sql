-- =============================================================================
-- V9: Role assignment table
-- =============================================================================
-- Replaces the coarse role/user_role schema with an auditable role_assignment
-- table that records who granted each role and when.
--
-- Design notes:
--   - ON DELETE RESTRICT on user_id: a user with active role grants cannot be
--     deleted (foreign-key restriction enforced by the database, not by code).
--   - role_name CHECK constraint mirrors the Java IdentityRole enum to prevent
--     out-of-vocabulary roles even from migration scripts.
--   - Unique constraint (user_id, role_name) prevents duplicate role grants.
--   - UUIDs generated as UUIDv7 in the application layer for B-tree locality.
-- =============================================================================

CREATE TABLE role_assignment (
    id          UUID          NOT NULL,
    user_id     UUID          NOT NULL,
    role_name   VARCHAR(20)   NOT NULL,
    granted_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    granted_by  UUID,                          -- null = system / bootstrap grant

    CONSTRAINT  pk_role_assignment           PRIMARY KEY (id),
    CONSTRAINT  fk_role_assignment_user      FOREIGN KEY (user_id)
                    REFERENCES app_user(id)   ON DELETE RESTRICT,
    CONSTRAINT  uq_role_assignment_user_role UNIQUE (user_id, role_name),
    CONSTRAINT  chk_role_assignment_name     CHECK (role_name IN
                    ('ADMIN', 'DISPATCHER', 'TECHNICIAN', 'MANAGER', 'CUSTOMER'))
);

CREATE INDEX idx_role_assignment_user_id ON role_assignment(user_id);

COMMENT ON TABLE  role_assignment          IS 'Audited record of each role grant; DELETE RESTRICT prevents silent grant loss on user deletion';
COMMENT ON COLUMN role_assignment.granted_by IS 'UUID of the user who performed the grant; NULL for system/bootstrap grants';
