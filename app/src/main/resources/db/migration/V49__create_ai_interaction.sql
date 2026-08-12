-- =============================================================================
-- V49: AI interaction audit log tables (WO-180)
-- Additive-only: new tables, indexes and no changes to existing schema.
--
-- ai_interaction is the append-only audit artefact for every AI call (copilot
-- questions, photo captions). Records are never deleted through application paths
-- except the automated purge job which physically removes rows past retain_until.
--
-- ai_interaction_rating captures a single helpfulness signal per interaction,
-- used by the Phase 4 exit gate (≥60% helpful within 90 days).
--
-- Retention periods are stored per-row so that changes to configuration do not
-- silently mutate historical retain_until values. The application.yml default
-- is indicative and pending compliance ratification.
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_interaction (
    id                  UUID            NOT NULL PRIMARY KEY,
    actor_user_id       UUID            NOT NULL,
    work_order_id       UUID,
    interaction_type    VARCHAR(30)     NOT NULL,
    provider            VARCHAR(80),
    model               VARCHAR(80),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    latency_ms          INTEGER,
    outcome             VARCHAR(30)     NOT NULL,
    prompt_tokens       INTEGER         NOT NULL DEFAULT 0,
    completion_tokens   INTEGER         NOT NULL DEFAULT 0,
    estimated_cost      NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    redaction_summary   JSONB           NOT NULL DEFAULT '{}'::jsonb,
    redactor_version    VARCHAR(20)     NOT NULL DEFAULT 'v1',
    redacted_prompt     TEXT,
    response_text       TEXT,
    response_truncated  BOOLEAN         NOT NULL DEFAULT false,
    grounding_basis     JSONB,
    classification      VARCHAR(20)     NOT NULL DEFAULT 'CONFIDENTIAL',
    retain_until        TIMESTAMPTZ     NOT NULL,

    CONSTRAINT chk_ai_interaction_type
        CHECK (interaction_type IN ('COPILOT_QUESTION', 'PHOTO_CAPTION')),
    CONSTRAINT chk_ai_interaction_outcome
        CHECK (outcome IN (
            'COMPLETED',
            'REFUSED_NO_GROUNDING',
            'DEGRADED',
            'CANCELLED',
            'CAPPED',
            'ERROR'
        )),
    CONSTRAINT chk_ai_interaction_classification
        CHECK (classification IN ('CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT chk_ai_interaction_latency_non_negative
        CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT chk_ai_interaction_tokens_non_negative
        CHECK (prompt_tokens >= 0 AND completion_tokens >= 0),
    CONSTRAINT chk_ai_interaction_cost_non_negative
        CHECK (estimated_cost >= 0)
);

CREATE TABLE IF NOT EXISTS ai_interaction_rating (
    id                  UUID        NOT NULL PRIMARY KEY,
    ai_interaction_id   UUID        NOT NULL REFERENCES ai_interaction(id),
    rating              VARCHAR(12) NOT NULL,
    rated_by            UUID        NOT NULL,
    rated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_ai_interaction_rating_value
        CHECK (rating IN ('HELPFUL', 'NOT_HELPFUL')),
    CONSTRAINT uq_ai_interaction_rating_one_per_interaction
        UNIQUE (ai_interaction_id)
);

-- Indexes on ai_interaction
CREATE INDEX IF NOT EXISTS idx_ai_interaction_actor_created
    ON ai_interaction (actor_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_interaction_work_order
    ON ai_interaction (work_order_id)
    WHERE work_order_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_interaction_type_created
    ON ai_interaction (interaction_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_interaction_outcome
    ON ai_interaction (outcome);

CREATE INDEX IF NOT EXISTS idx_ai_interaction_retain_until
    ON ai_interaction (retain_until)
    WHERE retain_until IS NOT NULL;
