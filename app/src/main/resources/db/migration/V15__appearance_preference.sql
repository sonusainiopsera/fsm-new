-- =============================================================================
-- V15: Per-account appearance preference persistence (WO-184)
-- =============================================================================
-- Expand-only migration (no destructive changes).
-- Runs as a pre-deploy task, backward-compatible with the prior application version.
--
-- Changes:
--   1. Add nullable appearance_preference column to app_user with a CHECK constraint
--      restricting values to the permitted vocabulary (LIGHT, DARK, SYSTEM).
--      Nullable: new accounts have no stored preference and resolve to LIGHT by convention.
--   2. Add the mirrored appearance_preference column to app_user_aud (Envers audit table).
--      The Envers @Audited annotation on AppUser will write this column on every revision.
-- =============================================================================

-- 1. Add appearance_preference to app_user (expand phase — nullable, no backfill required)
ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS appearance_preference VARCHAR(10);

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_appearance_preference
        CHECK (appearance_preference IN ('LIGHT', 'DARK', 'SYSTEM'));

COMMENT ON COLUMN app_user.appearance_preference IS
    'Internal — per-account appearance setting. NULL resolves to LIGHT (default). '
    'Values: LIGHT, DARK, SYSTEM. Updated via PUT /api/v1/users/me/preferences. '
    'Audited in app_user_aud (BR-21). Mirrored in browser localStorage (key: fs-appearance).';

-- 2. Mirror the column in the Envers audit table so revisions capture changes
ALTER TABLE app_user_aud
    ADD COLUMN IF NOT EXISTS appearance_preference VARCHAR(10);

COMMENT ON COLUMN app_user_aud.appearance_preference IS
    'Envers audit mirror of app_user.appearance_preference';
