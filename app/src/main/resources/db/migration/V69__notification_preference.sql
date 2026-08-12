-- =============================================================================
-- V69: Per-user notification channel preferences (WO-197)
-- =============================================================================
-- Expand-only: no columns dropped, no existing tables altered.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- notification_preference: per-user, per-category, per-channel opt-in/out
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_preference (
    id          UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    category    TEXT        NOT NULL CHECK (category IN (
                    'WO_ASSIGNED', 'WO_REASSIGNED', 'WO_STATUS_CHANGE',
                    'SLA_RISK', 'SLA_BREACH', 'APPOINTMENT_CHANGED',
                    'CERTIFICATION_EXPIRING', 'CERTIFICATION_EXPIRED',
                    'WO_DUPLICATE_LINKED', 'WO_REJECTED', 'CLOSURE_SURVEY'
                )),
    channel     VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
    enabled     BOOLEAN     NOT NULL DEFAULT true,
    version     INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_preference PRIMARY KEY (id),
    CONSTRAINT uq_notification_preference_key UNIQUE (user_id, category, channel)
);

CREATE INDEX IF NOT EXISTS idx_notification_preference_user
    ON notification_preference (user_id);

CREATE INDEX IF NOT EXISTS idx_notification_preference_user_category
    ON notification_preference (user_id, category);

-- ---------------------------------------------------------------------------
-- notification_preference_AUD: Envers audit table (immutable via grants below)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_preference_aud (
    id          UUID        NOT NULL,
    rev         INTEGER     NOT NULL,
    revtype     SMALLINT,
    user_id     UUID,
    category    TEXT,
    channel     VARCHAR(10),
    enabled     BOOLEAN,
    version     INTEGER,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,

    CONSTRAINT pk_notification_preference_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_notification_preference_aud_rev
        FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

-- ---------------------------------------------------------------------------
-- Grants — app role gets SELECT and INSERT only; no UPDATE/DELETE (immutability)
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON notification_preference     TO fieldservice_app;
GRANT SELECT, INSERT         ON notification_preference_aud TO fieldservice_app;
