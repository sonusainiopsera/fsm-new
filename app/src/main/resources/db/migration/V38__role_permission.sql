-- V38: Role-permission matrix table
-- Configurable role-to-permission-code mappings.  Allows administrators to adjust
-- which roles hold which named operations without a code release.
--
-- Permission codes follow the pattern "{resource}:{action}" (e.g. SLA_POLICY:WRITE).
-- Enforcement is via RolePermissionEvaluator which checks this table at runtime.
--
-- Guard: the last ADMIN:ADMIN_ACCESS entry cannot be removed (checked in service layer).

CREATE TABLE role_permission (
    id              UUID         NOT NULL,
    role_name       VARCHAR(50)  NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    granted_by      VARCHAR(255),
    granted_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_by      VARCHAR(255),
    revoked_at      TIMESTAMPTZ,
    version         INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_role_permission          PRIMARY KEY (id),
    CONSTRAINT uq_role_permission_pair     UNIQUE (role_name, permission_code),
    CONSTRAINT chk_role_permission_role    CHECK (role_name IN (
        'ADMIN','DISPATCHER','TECHNICIAN','MANAGER','CUSTOMER','PRIVACY_ADMIN'))
);

CREATE INDEX idx_role_permission_role   ON role_permission (role_name, active);
CREATE INDEX idx_role_permission_code   ON role_permission (permission_code, active);

-- Envers audit table
CREATE TABLE role_permission_aud (
    id              UUID         NOT NULL,
    rev             INTEGER      NOT NULL REFERENCES REVINFO (rev),
    revtype         SMALLINT,
    role_name       VARCHAR(50),
    permission_code VARCHAR(100),
    active          BOOLEAN,
    granted_by      VARCHAR(255),
    granted_at      TIMESTAMPTZ,
    revoked_by      VARCHAR(255),
    revoked_at      TIMESTAMPTZ,
    version         INTEGER,
    CONSTRAINT pk_role_permission_aud PRIMARY KEY (id, rev)
);

-- Seed default permission grants for each role.
-- ADMIN has all permissions.  Other roles have read-only by default.
-- UUID prefix 00000000-0000-7038-8000-XXXXXXXXXXXX for V38 seed rows.
INSERT INTO role_permission (id, role_name, permission_code, granted_by, granted_at) VALUES
    -- ADMIN: full access
    ('00000000-0000-7038-8000-000000000001', 'ADMIN', 'ADMIN_ACCESS',        'system', NOW()),
    ('00000000-0000-7038-8000-000000000002', 'ADMIN', 'SLA_POLICY:READ',     'system', NOW()),
    ('00000000-0000-7038-8000-000000000003', 'ADMIN', 'SLA_POLICY:WRITE',    'system', NOW()),
    ('00000000-0000-7038-8000-000000000004', 'ADMIN', 'ROLE_MATRIX:READ',    'system', NOW()),
    ('00000000-0000-7038-8000-000000000005', 'ADMIN', 'ROLE_MATRIX:WRITE',   'system', NOW()),
    -- DISPATCHER: read SLA, no write
    ('00000000-0000-7038-8000-000000000010', 'DISPATCHER', 'SLA_POLICY:READ', 'system', NOW()),
    -- MANAGER: read SLA, no write
    ('00000000-0000-7038-8000-000000000011', 'MANAGER', 'SLA_POLICY:READ',   'system', NOW()),
    -- TECHNICIAN: read SLA, no write
    ('00000000-0000-7038-8000-000000000012', 'TECHNICIAN', 'SLA_POLICY:READ', 'system', NOW());
