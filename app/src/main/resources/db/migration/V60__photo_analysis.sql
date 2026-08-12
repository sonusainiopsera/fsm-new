-- V60: Photo analysis columns and retention purge support (WO-181).
--
-- Extends work_order_photo with:
--   content_type          — allow-listed image type, nullable for backward compat with existing rows
--   size_bytes            — verified object size after HEAD check
--   classification        — data classification, defaults to CONFIDENTIAL
--   analysis_interaction_id — FK to ai_interaction when analysis has been run
--   override_classification — how the technician used the AI suggestion
--   similarity_score      — Jaccard similarity between suggestion and final description
--
-- Also extends work_order_photo_aud (Envers) with the same columns.

-- ── Extend work_order_photo ─────────────────────────────────────────────────

ALTER TABLE work_order_photo
    ADD COLUMN IF NOT EXISTS content_type              TEXT,
    ADD COLUMN IF NOT EXISTS size_bytes                BIGINT,
    ADD COLUMN IF NOT EXISTS classification            TEXT NOT NULL DEFAULT 'CONFIDENTIAL',
    ADD COLUMN IF NOT EXISTS analysis_interaction_id   UUID,
    ADD COLUMN IF NOT EXISTS override_classification   TEXT,
    ADD COLUMN IF NOT EXISTS similarity_score          NUMERIC(5,4);

ALTER TABLE work_order_photo
    ADD CONSTRAINT ck_wop_content_type
        CHECK (content_type IS NULL
            OR content_type IN ('image/jpeg', 'image/png', 'image/webp'));

ALTER TABLE work_order_photo
    ADD CONSTRAINT ck_wop_size_bytes
        CHECK (size_bytes IS NULL OR size_bytes > 0);

ALTER TABLE work_order_photo
    ADD CONSTRAINT ck_wop_classification
        CHECK (classification IN ('CONFIDENTIAL', 'RESTRICTED', 'INTERNAL'));

ALTER TABLE work_order_photo
    ADD CONSTRAINT ck_wop_override_classification
        CHECK (override_classification IS NULL
            OR override_classification IN (
                'ACCEPTED_UNCHANGED', 'LIGHTLY_EDITED',
                'SUBSTANTIALLY_REWRITTEN', 'DISCARDED'));

ALTER TABLE work_order_photo
    ADD CONSTRAINT ck_wop_similarity_score
        CHECK (similarity_score IS NULL
            OR (similarity_score >= 0 AND similarity_score <= 1));

ALTER TABLE work_order_photo
    ADD CONSTRAINT fk_wop_analysis_interaction
        FOREIGN KEY (analysis_interaction_id)
        REFERENCES ai_interaction (id);

-- Index for analysis interaction lookup (avoid full table scan on FK check)
CREATE INDEX IF NOT EXISTS idx_wop_analysis_interaction
    ON work_order_photo (analysis_interaction_id)
    WHERE analysis_interaction_id IS NOT NULL;

-- Index for retention purge job (scan for expired rows without full table scan)
CREATE INDEX IF NOT EXISTS idx_wop_retain_until
    ON work_order_photo (retain_until)
    WHERE retain_until IS NOT NULL;

-- ── Extend Envers audit table ───────────────────────────────────────────────
-- Envers requires the same columns in *_aud; add as nullable to avoid back-fill.

ALTER TABLE work_order_photo_aud
    ADD COLUMN IF NOT EXISTS content_type              TEXT,
    ADD COLUMN IF NOT EXISTS size_bytes                BIGINT,
    ADD COLUMN IF NOT EXISTS classification            TEXT,
    ADD COLUMN IF NOT EXISTS analysis_interaction_id   UUID,
    ADD COLUMN IF NOT EXISTS override_classification   TEXT,
    ADD COLUMN IF NOT EXISTS similarity_score          NUMERIC(5,4);
