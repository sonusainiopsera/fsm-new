-- V47: AI interaction audit log (WO-180)
--
-- Creates ai_interaction (append-only audit artefact for every copilot / photo-caption
-- call) and ai_interaction_rating (single helpfulness rating per interaction).
--
-- Design notes:
--   - UUIDv7 primary keys for time-ordered B-tree locality.
--   - CHECK constraints on the interaction_type and outcome vocabularies.
--   - ai_interaction has no UPDATE-capable application columns — only the rating
--     association can be added post-insert (via ai_interaction_rating).
--   - retain_until is computed at insert from ai.audit.retention-days; the value is
--     stored on each row so retention changes never silently mutate historical rows.
--   - redaction_summary is JSONB so evidence is machine-queryable for compliance review.
--   - response_truncated tracks whether long responses were capped at the storage limit.
--   - grounding_basis is JSONB matching the CopilotSseEvents.BasisEntry structure.

CREATE TABLE ai_interaction (
    id                  UUID            NOT NULL,
    interaction_type    TEXT            NOT NULL,
    actor_user_id       UUID            NOT NULL,
    work_order_id       UUID,
    provider            TEXT,
    model               TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    latency_ms          INTEGER,
    outcome             TEXT            NOT NULL,
    prompt_tokens       INTEGER         NOT NULL DEFAULT 0,
    completion_tokens   INTEGER         NOT NULL DEFAULT 0,
    estimated_cost      NUMERIC(12, 6),
    redaction_summary   JSONB           NOT NULL DEFAULT '{}'::jsonb,
    redactor_version    TEXT            NOT NULL DEFAULT 'unknown',
    redacted_prompt     TEXT,
    response_text       TEXT,
    response_truncated  BOOLEAN         NOT NULL DEFAULT FALSE,
    grounding_basis     JSONB,
    classification      TEXT            NOT NULL DEFAULT 'CONFIDENTIAL',
    retain_until        TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_ai_interaction         PRIMARY KEY (id),
    CONSTRAINT ck_ai_interaction_type    CHECK (interaction_type IN ('COPILOT_QUESTION', 'PHOTO_CAPTION')),
    CONSTRAINT ck_ai_interaction_outcome CHECK (outcome IN (
        'COMPLETED', 'REFUSED_NO_GROUNDING', 'DEGRADED', 'CANCELLED', 'CAPPED', 'ERROR'
    )),
    CONSTRAINT ck_ai_classification      CHECK (classification IN ('CONFIDENTIAL', 'RESTRICTED', 'INTERNAL'))
);

COMMENT ON TABLE  ai_interaction IS 'Append-only audit artefact for every AI interaction; no application path may UPDATE or DELETE rows except attaching a rating.';
COMMENT ON COLUMN ai_interaction.retain_until   IS 'Computed at insert from ai.audit.retention-days config; purge job physically deletes rows past this timestamp.';
COMMENT ON COLUMN ai_interaction.redaction_summary IS 'Per-category PII substitution counts produced by WO-081 PiiRedactor; evidence is reproducible via redactor_version.';
COMMENT ON COLUMN ai_interaction.response_truncated IS 'TRUE when response_text was capped at the configured storage limit.';

CREATE TABLE ai_interaction_rating (
    id                  UUID            NOT NULL,
    ai_interaction_id   UUID            NOT NULL,
    rating              TEXT            NOT NULL,
    rated_by            UUID            NOT NULL,
    rated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_ai_interaction_rating         PRIMARY KEY (id),
    CONSTRAINT fk_air_interaction               FOREIGN KEY (ai_interaction_id)
                                                    REFERENCES ai_interaction (id) ON DELETE CASCADE,
    CONSTRAINT uq_ai_interaction_rating         UNIQUE (ai_interaction_id),
    CONSTRAINT ck_ai_interaction_rating_value   CHECK (rating IN ('HELPFUL', 'NOT_HELPFUL'))
);

COMMENT ON TABLE ai_interaction_rating IS 'Single helpfulness rating per interaction; unique constraint prevents double-recording.';

-- ── Indexes ────────────────────────────────────────────────────────────────────────────
-- Composite index covering the most common actor dashboard and audit-export queries.
CREATE INDEX idx_ai_interaction_actor_created
    ON ai_interaction (actor_user_id, created_at DESC);

-- Work-order drill-down (manager queries, copilot session grouping).
CREATE INDEX idx_ai_interaction_work_order
    ON ai_interaction (work_order_id)
    WHERE work_order_id IS NOT NULL;

-- Interaction type + time (filtering to COPILOT_QUESTION or PHOTO_CAPTION windows).
CREATE INDEX idx_ai_interaction_type_created
    ON ai_interaction (interaction_type, created_at DESC);

-- Outcome distribution queries used by the metrics endpoint.
CREATE INDEX idx_ai_interaction_outcome
    ON ai_interaction (outcome);

-- Purge job: scan for expired rows in bounded batches without a full table scan.
CREATE INDEX idx_ai_interaction_retain_until
    ON ai_interaction (retain_until)
    WHERE retain_until IS NOT NULL;
