-- V3: Notification preference table with Envers audit shadow table
--
-- notification_preference: one row per (user_id, category, channel) triple.
-- A missing row means "default-on" — all channels are enabled by default.
--
-- notification_preference_AUD: Envers audit shadow written on every DML.
-- revtype: 0=ADD, 1=MOD, 2=DEL (Envers convention).

CREATE TABLE notification_preference (
    id         UUID        NOT NULL,
    user_id    UUID        NOT NULL REFERENCES app_user(id),
    category   TEXT        NOT NULL,
    channel    TEXT        NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    version    INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notification_preference PRIMARY KEY (id),
    CONSTRAINT uq_notification_preference_user_category_channel
        UNIQUE (user_id, category, channel),
    CONSTRAINT chk_notification_preference_category CHECK (category IN (
        'WORK_ORDER_CREATED',
        'WORK_ORDER_ASSIGNED',
        'WORK_ORDER_UPDATED',
        'WORK_ORDER_COMPLETED',
        'SLA_AT_RISK',
        'SLA_BREACH',
        'PARTS_REQUEST',
        'TECHNICIAN_EN_ROUTE'
    )),
    CONSTRAINT chk_notification_preference_channel CHECK (channel IN (
        'EMAIL', 'SMS', 'IN_APP', 'PUSH'
    ))
);

CREATE INDEX idx_notification_preference_user_id
    ON notification_preference (user_id);

CREATE INDEX idx_notification_preference_user_category
    ON notification_preference (user_id, category);

COMMENT ON TABLE  notification_preference          IS 'Explicit per-user notification channel preferences. Absent rows default to enabled=true.';
COMMENT ON COLUMN notification_preference.version  IS 'Hibernate optimistic-lock version counter';

-- ── Envers audit table ───────────────────────────────────────────────────────

CREATE TABLE notification_preference_AUD (
    id         UUID     NOT NULL,
    rev        INTEGER  NOT NULL REFERENCES revinfo(rev),
    revtype    SMALLINT,
    user_id    UUID,
    category   TEXT,
    channel    TEXT,
    enabled    BOOLEAN,
    version    INTEGER,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,

    CONSTRAINT pk_notification_preference_aud PRIMARY KEY (id, rev)
);

CREATE INDEX idx_notification_preference_aud_rev
    ON notification_preference_AUD (rev);

COMMENT ON TABLE notification_preference_AUD IS 'Hibernate Envers audit trail for notification_preference. revtype: 0=INSERT 1=UPDATE 2=DELETE';
