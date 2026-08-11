-- V8__identity_core.sql
-- Extends app_user for the full identity model and adds role_assignment,
-- refresh_token_family, and refresh_token tables.
-- Expand-only: no DROP of non-null columns, no tightening constraints.
-- Invitation and federation tables are deferred pending ratification (Q12).

-- ==============================================================
-- Extend app_user
-- ==============================================================

-- Make password_hash nullable to support future federated / external-IdP flows (Q12).
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;

-- Make legacy full_name nullable; display_name is the canonical identity field going forward.
ALTER TABLE app_user ALTER COLUMN full_name DROP NOT NULL;

-- display_name: human-readable name presented in UI and emails.
ALTER TABLE app_user ADD COLUMN display_name    VARCHAR(255);

-- external_subject: opaque subject claim from an external IdP (e.g. OIDC sub).
-- Reserved column; not populated until federation is ratified.
ALTER TABLE app_user ADD COLUMN external_subject VARCHAR(255);

-- updated_at: tracks the last mutation timestamp for the identity record.
ALTER TABLE app_user ADD COLUMN updated_at TIMESTAMPTZ;

-- Replace simple UNIQUE with a case-insensitive functional index.
-- 'Alice@example.com' and 'alice@example.com' must collide on registration.
ALTER TABLE app_user DROP CONSTRAINT uq_app_user_email;
CREATE UNIQUE INDEX uq_app_user_email_ci ON app_user (lower(email));

-- ==============================================================
-- role_assignment
-- Role vocabulary enforced by CHECK so an out-of-vocabulary role cannot be
-- persisted even by a direct SQL statement or migration script.
-- ON DELETE RESTRICT: deleting a user with active grants is refused by the FK
-- rather than cascading and destroying grant history.
-- ==============================================================
CREATE TABLE role_assignment (
    id         UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    role_name  VARCHAR(50)  NOT NULL,
    granted_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    granted_by VARCHAR(255),
    CONSTRAINT pk_role_assignment           PRIMARY KEY (id),
    CONSTRAINT fk_role_assignment_user      FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT uq_role_assignment_user_role UNIQUE (user_id, role_name),
    CONSTRAINT chk_role_assignment_role     CHECK (role_name IN (
        'ADMIN', 'DISPATCHER', 'TECHNICIAN', 'MANAGER', 'CUSTOMER'
    ))
);

CREATE INDEX idx_role_assignment_user ON role_assignment (user_id);

-- ==============================================================
-- refresh_token_family
-- Groups tokens issued in one session; revoking the family invalidates all
-- tokens without scanning individual token rows.
-- ==============================================================
CREATE TABLE refresh_token_family (
    id             UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(255),
    CONSTRAINT pk_refresh_token_family      PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_family_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT
);

CREATE INDEX idx_rtf_user ON refresh_token_family (user_id);

-- ==============================================================
-- refresh_token
-- Stores the SHA-256 hex hash (64 chars) of the opaque handle issued to the
-- client. The plaintext handle must never touch the database.
-- ==============================================================
CREATE TABLE refresh_token (
    id             UUID        NOT NULL,
    family_id      UUID        NOT NULL,
    -- SHA-256 hex of the opaque handle (CHAR enforces exactly 64 chars)
    token_hash     CHAR(64)    NOT NULL,
    issued_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(255),
    CONSTRAINT pk_refresh_token        PRIMARY KEY (id),
    CONSTRAINT uq_refresh_token_hash   UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id) REFERENCES refresh_token_family (id)
);

CREATE INDEX idx_refresh_token_hash   ON refresh_token (token_hash);
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
