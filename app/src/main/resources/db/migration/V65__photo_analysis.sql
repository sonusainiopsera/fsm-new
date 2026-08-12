-- V65: Photo-based issue analysis support (WO-181)
-- Expand-only migration: adds nullable columns to existing tables and a new companion table.
-- =============================================================================

-- Extend work_order_photo with content-type, size and analysis interaction link
ALTER TABLE work_order_photo
    ADD COLUMN IF NOT EXISTS content_type         TEXT,
    ADD COLUMN IF NOT EXISTS size_bytes           BIGINT,
    ADD COLUMN IF NOT EXISTS analysis_interaction_id UUID REFERENCES ai_interaction(id);

-- Allow-list constraint on content_type for new rows (NULL permitted for rows from V58)
ALTER TABLE work_order_photo
    DROP CONSTRAINT IF EXISTS work_order_photo_content_type_chk;
ALTER TABLE work_order_photo
    ADD CONSTRAINT work_order_photo_content_type_chk
        CHECK (content_type IS NULL OR content_type IN ('image/jpeg', 'image/png', 'image/webp'));

-- Partial index drives analysis-pending queries efficiently
CREATE INDEX IF NOT EXISTS idx_work_order_photo_retain_until
    ON work_order_photo(retain_until);

-- Mirror new columns in Envers audit table
ALTER TABLE work_order_photo_aud
    ADD COLUMN IF NOT EXISTS content_type         TEXT,
    ADD COLUMN IF NOT EXISTS size_bytes           BIGINT,
    ADD COLUMN IF NOT EXISTS analysis_interaction_id UUID;

-- photo_description_override: records technician's final description choice
-- and the classifier's verdict (override_classification, similarity_score).
-- One row per (work_order_photo, interactionId) pair — idempotent on (work_order_photo_id, ai_interaction_id).
CREATE TABLE IF NOT EXISTS photo_description_override (
    id                      UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    work_order_photo_id     UUID        NOT NULL REFERENCES work_order_photo(id) ON DELETE CASCADE,
    ai_interaction_id       UUID        REFERENCES ai_interaction(id),
    description             TEXT,
    override_classification TEXT        NOT NULL,
    similarity_score        NUMERIC(6, 5),
    recorded_by             UUID        NOT NULL,
    recorded_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pdo_classification_chk CHECK (
        override_classification IN ('ACCEPTED_UNCHANGED', 'LIGHTLY_EDITED',
                                    'SUBSTANTIALLY_REWRITTEN', 'DISCARDED')
    ),
    -- At most one override per (photo, interaction); a null interaction_id is allowed for
    -- descriptions recorded without an AI suggestion (feature flag off scenario).
    CONSTRAINT pdo_unique_per_interaction
        UNIQUE (work_order_photo_id, ai_interaction_id)
);

CREATE INDEX IF NOT EXISTS idx_pdo_work_order_photo ON photo_description_override(work_order_photo_id);
CREATE INDEX IF NOT EXISTS idx_pdo_ai_interaction   ON photo_description_override(ai_interaction_id)
    WHERE ai_interaction_id IS NOT NULL;
