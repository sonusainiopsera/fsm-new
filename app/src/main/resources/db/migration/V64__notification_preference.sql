-- V64__notification_preference.sql
-- Per-user notification channel preferences with Envers audit trail.
-- Expand-only: adds two tables, no changes to existing tables.

-- ── notification_preference ───────────────────────────────────────────────────

CREATE TABLE notification_preference (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    category    VARCHAR(50)  NOT NULL,
    channel     VARCHAR(20)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    version     INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notification_preference
        PRIMARY KEY (id),

    CONSTRAINT uq_notification_preference_user_category_channel
        UNIQUE (user_id, category, channel),

    CONSTRAINT chk_np_category CHECK (category IN (
        'WORK_ORDER_ASSIGNMENT',
        'SLA_ALERT',
        'CUSTOMER_STATUS_UPDATE',
        'CSAT_SURVEY',
        'APPOINTMENT_UPDATE',
        'PORTAL_REQUEST_UPDATE',
        'CERTIFICATION_ALERT'
    )),

    CONSTRAINT chk_np_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP'))
);

CREATE INDEX idx_np_user_id         ON notification_preference (user_id);
CREATE INDEX idx_np_user_category   ON notification_preference (user_id, category);

-- ── notification_preference_AUD (Envers audit shadow table) ──────────────────
-- Mirrors notification_preference; all data columns nullable to support DEL revisions.
-- Wired to the existing REVINFO table and revinfo_seq.

CREATE TABLE notification_preference_aud (
    id          UUID        NOT NULL,
    REV         INTEGER     NOT NULL,
    REVTYPE     SMALLINT,

    -- data columns
    user_id     UUID,
    category    VARCHAR(50),
    channel     VARCHAR(20),
    enabled     BOOLEAN,
    version     INTEGER,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,

    CONSTRAINT pk_notification_preference_aud
        PRIMARY KEY (id, REV),
    CONSTRAINT fk_notification_preference_aud_rev
        FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX idx_np_aud_user_id ON notification_preference_aud (user_id);
