-- V12__login_audit.sql
-- Audit table for every login attempt (success or failure).
-- Every row is written atomically with its Envers revision and outbox event
-- so authentication history is immutable and cannot exist without an audit record.

CREATE TABLE login_audit (
    id           UUID         NOT NULL,
    email_hash   CHAR(64)     NOT NULL,  -- SHA-256 hex of canonicalized email (PII masked)
    user_id      UUID,                   -- NULL when email is not found
    outcome      VARCHAR(30)  NOT NULL,  -- see CHECK constraint below
    trace_id     VARCHAR(255),
    client_ip    VARCHAR(64),
    attempted_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_login_audit PRIMARY KEY (id),
    CONSTRAINT chk_login_outcome CHECK (outcome IN (
        'SUCCESS',
        'WRONG_PASSWORD',
        'UNKNOWN_EMAIL',
        'INACTIVE',
        'LOCKED',
        'GRANTLESS',
        'LOCKOUT_STORE_UNAVAILABLE'
    ))
);

-- Supports: audit queries per account (by email_hash), time-range scans for retention
CREATE INDEX idx_login_audit_email_hash   ON login_audit (email_hash);
CREATE INDEX idx_login_audit_attempted_at ON login_audit (attempted_at);

-- Envers audit table for login_audit (append-only; UPDATE/DELETE revoked below)
CREATE TABLE login_audit_aud (
    id           UUID         NOT NULL,
    REV          INTEGER      NOT NULL,
    REVTYPE      SMALLINT,
    email_hash   CHAR(64),
    user_id      UUID,
    outcome      VARCHAR(30),
    trace_id     VARCHAR(255),
    client_ip    VARCHAR(64),
    attempted_at TIMESTAMPTZ,
    CONSTRAINT pk_login_audit_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_login_audit_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- Least-privilege grants: runtime role may only insert rows and read history
GRANT SELECT, INSERT ON login_audit     TO fieldservice;
GRANT SELECT, INSERT ON login_audit_aud TO fieldservice;

REVOKE UPDATE, DELETE, TRUNCATE ON login_audit     FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON login_audit_aud FROM PUBLIC;
