-- V2: Minimal application user table
--
-- app_user stores just enough user identity for the field service platform.
-- The notification_preference table references this table via a foreign key
-- on user_id.
--
-- Note: "user" is a reserved keyword in PostgreSQL, so the table is named
-- "app_user" to avoid quoting requirements.

CREATE TABLE IF NOT EXISTS app_user (
    id         UUID         NOT NULL,
    email      VARCHAR(255) NOT NULL,
    full_name  VARCHAR(255),
    role       VARCHAR(50)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_app_user      PRIMARY KEY (id),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT chk_app_user_role CHECK (role IN (
        'ADMIN', 'MANAGER', 'DISPATCHER', 'TECHNICIAN', 'CUSTOMER'
    ))
);

COMMENT ON TABLE  app_user           IS 'Platform users; referenced by notification_preference.user_id';
COMMENT ON COLUMN app_user.role      IS 'Coarse-grained role; fine-grained permissions live in the auth provider';
