-- V32__portal_linkage.sql
-- Portal customer account linkage model (WO-169).
-- Creates portal_account_user and portal_invitation tables with UUIDv7 PKs,
-- Envers AUD tables, and required indexes.
-- REVINFO and revinfo_seq already exist (created in V5__audit_tables.sql).

-- ============================================================
-- portal_account_user
-- Binds an identity user to exactly one customer account.
-- UNIQUE(user_id) enforces the one-to-one linkage invariant at the DB level.
-- contact_email and contact_name store Base64(AES-256-GCM(value)) per the
-- platform EncryptedStringConverter (BR-23 Confidential classification).
-- ============================================================
CREATE TABLE portal_account_user (
    id           UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    account_id   UUID         NOT NULL,
    status       VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    activated_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version      INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_portal_account_user      PRIMARY KEY (id),
    CONSTRAINT uq_portal_account_user_uid  UNIQUE (user_id),
    CONSTRAINT fk_portal_account_user_uid  FOREIGN KEY (user_id)   REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_portal_account_user_acc  FOREIGN KEY (account_id) REFERENCES customer(id) ON DELETE RESTRICT,
    CONSTRAINT ck_portal_account_user_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED'))
);

CREATE INDEX idx_portal_account_user_account ON portal_account_user(account_id);

-- ============================================================
-- portal_invitation
-- Invitation record for invitation-based portal onboarding.
-- contact_email and contact_name are stored encrypted (AES-256-GCM Base64)
-- so the raw value is never visible in the database.
-- token_hash stores SHA-256 hex of the opaque token; the plaintext token
-- is returned once at issuance and is never stored.
-- ============================================================
CREATE TABLE portal_invitation (
    id            UUID          NOT NULL,
    account_id    UUID          NOT NULL,
    contact_email VARCHAR(2048),
    contact_name  VARCHAR(1024),
    token_hash    CHAR(64)      NOT NULL,
    expires_at    TIMESTAMPTZ   NOT NULL,
    consumed_at   TIMESTAMPTZ,
    created_by    UUID,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version       INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT pk_portal_invitation         PRIMARY KEY (id),
    CONSTRAINT uq_portal_invitation_token   UNIQUE (token_hash),
    CONSTRAINT fk_portal_invitation_account FOREIGN KEY (account_id) REFERENCES customer(id) ON DELETE RESTRICT
);

CREATE INDEX idx_portal_invitation_account ON portal_invitation(account_id);

-- ============================================================
-- Envers AUD tables
-- (REVINFO and revinfo_seq already exist from V5__audit_tables.sql)
-- Audited columns are nullable to capture partial state at deletion.
-- ============================================================
CREATE TABLE portal_account_user_aud (
    id           UUID        NOT NULL,
    REV          INTEGER     NOT NULL,
    REVTYPE      SMALLINT,
    user_id      UUID,
    account_id   UUID,
    status       VARCHAR(30),
    activated_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ,
    version      INTEGER,
    CONSTRAINT pk_portal_account_user_aud      PRIMARY KEY (id, REV),
    CONSTRAINT fk_portal_account_user_aud_rev  FOREIGN KEY (REV) REFERENCES REVINFO(REV)
);

CREATE TABLE portal_invitation_aud (
    id            UUID          NOT NULL,
    REV           INTEGER       NOT NULL,
    REVTYPE       SMALLINT,
    account_id    UUID,
    contact_email VARCHAR(2048),
    contact_name  VARCHAR(1024),
    token_hash    CHAR(64),
    expires_at    TIMESTAMPTZ,
    consumed_at   TIMESTAMPTZ,
    created_by    UUID,
    created_at    TIMESTAMPTZ,
    version       INTEGER,
    CONSTRAINT pk_portal_invitation_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_portal_invitation_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO(REV)
);
