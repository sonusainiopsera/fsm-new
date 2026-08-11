-- V14__appearance_preference.sql
-- Expand-only migration: adds appearance_preference to app_user and its Envers
-- audit table, and creates the structured preference change audit table.
-- No destructive change, no NOT NULL tightening, no default backfill.

-- ============================================================
-- Extend app_user with nullable appearance_preference
-- CHECK constraint mirrors the AppearancePreference enum vocabulary.
-- Nullable: new accounts default to null which resolves to LIGHT at read time.
-- ============================================================
ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS appearance_preference VARCHAR(10)
        CONSTRAINT chk_app_user_appearance
            CHECK (appearance_preference IN ('LIGHT', 'DARK', 'SYSTEM'));

-- ============================================================
-- Mirror column in Envers audit table
-- Must match the source column type; nullable to support DEL revisions.
-- ============================================================
ALTER TABLE app_user_aud
    ADD COLUMN IF NOT EXISTS appearance_preference VARCHAR(10);

-- ============================================================
-- Structured preference change audit
-- Written in the same transaction as every preference mutation.
-- actor_id / actor_role / trace_id come from the authenticated request context.
-- Data classification: Internal (BR-23) — no PII, no Restricted fields.
-- ============================================================
CREATE TABLE user_preference_audit (
    id           UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    field_name   VARCHAR(100) NOT NULL,
    old_value    VARCHAR(50),
    new_value    VARCHAR(50),
    actor_id     UUID         NOT NULL,
    actor_role   VARCHAR(50),
    trace_id     VARCHAR(36),
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_preference_audit      PRIMARY KEY (id),
    CONSTRAINT fk_user_preference_audit_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE INDEX idx_user_preference_audit_user
    ON user_preference_audit (user_id, occurred_at DESC);
