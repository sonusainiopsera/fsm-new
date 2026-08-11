-- =============================================================================
-- V38: Add ratified flag to sla_policy; role-permission matrix tables (WO-198)
-- Expand-only: adds columns with defaults; never drops.
-- =============================================================================

-- Add ratified flag: false = placeholder value, true = stakeholder-ratified
ALTER TABLE sla_policy
    ADD COLUMN IF NOT EXISTS ratified BOOLEAN NOT NULL DEFAULT false;

-- Mirror into Envers audit table (version excluded per Envers config)
ALTER TABLE sla_policy_aud
    ADD COLUMN IF NOT EXISTS ratified BOOLEAN;

-- ─── Role-permission matrix ───────────────────────────────────────────────────
-- One row per role; permissions stored as a comma-delimited string so Envers
-- can capture before/after values without an additional join.
CREATE TABLE IF NOT EXISTS role_permission_matrix (
    id          UUID        NOT NULL,
    role_name   VARCHAR(20) NOT NULL,
    permissions TEXT        NOT NULL DEFAULT '',
    version     INTEGER     NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_role_permission_matrix PRIMARY KEY (id),
    CONSTRAINT uq_role_permission_matrix_role UNIQUE (role_name)
);

-- Envers audit table for role_permission_matrix
CREATE TABLE IF NOT EXISTS role_permission_matrix_aud (
    id          UUID        NOT NULL,
    rev         INTEGER     NOT NULL,
    revtype     SMALLINT    NOT NULL,
    role_name   VARCHAR(20),
    permissions TEXT,
    updated_at  TIMESTAMPTZ,
    CONSTRAINT pk_role_permission_matrix_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_role_permission_matrix_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- Seed initial role-permission matrix (reflect current @PreAuthorize annotations)
INSERT INTO role_permission_matrix (id, role_name, permissions) VALUES
    (gen_random_uuid(), 'ADMIN',        'sla:read,sla:write,workorder:read,workorder:write,identity:read,identity:write,role-matrix:read,role-matrix:write'),
    (gen_random_uuid(), 'DISPATCHER',   'workorder:read,workorder:write'),
    (gen_random_uuid(), 'TECHNICIAN',   'workorder:read'),
    (gen_random_uuid(), 'MANAGER',      'workorder:read,workorder:write'),
    (gen_random_uuid(), 'CUSTOMER',     'workorder:read'),
    (gen_random_uuid(), 'PRIVACY_ADMIN','privacy:read,privacy:write')
ON CONFLICT (role_name) DO NOTHING;
