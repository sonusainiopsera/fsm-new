-- recommendation_snapshot: one immutable row per recommendation generation.
-- Insert-only: no UPDATE or DELETE paths exist in the application.
CREATE TABLE recommendation_snapshot (
    id                       UUID         NOT NULL,
    work_order_id            UUID         NOT NULL REFERENCES work_order(id),
    generated_at             TIMESTAMPTZ  NOT NULL,
    generated_by             UUID,
    weight_set_version       TEXT,
    travel_estimate_degraded BOOLEAN      NOT NULL DEFAULT FALSE,
    parts_data_degraded      BOOLEAN      NOT NULL DEFAULT FALSE,
    candidate_pool_size      INTEGER      NOT NULL DEFAULT 0,
    truncated                BOOLEAN      NOT NULL DEFAULT FALSE,
    version                  INTEGER      NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_recommendation_snapshot PRIMARY KEY (id)
);

-- recommendation_snapshot_candidate: one row per ranked candidate in a snapshot.
-- ON DELETE RESTRICT ensures the snapshot header cannot be removed while candidates reference it.
CREATE TABLE recommendation_snapshot_candidate (
    id               UUID          NOT NULL,
    snapshot_id      UUID          NOT NULL REFERENCES recommendation_snapshot(id) ON DELETE RESTRICT,
    technician_id    UUID          NOT NULL,
    rank             INTEGER       NOT NULL,
    score            NUMERIC(7, 6) NOT NULL,
    factor_breakdown JSONB         NOT NULL,
    version          INTEGER       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_recommendation_snapshot_candidate PRIMARY KEY (id)
);

CREATE INDEX idx_recsnapshot_wo_generated
    ON recommendation_snapshot (work_order_id, generated_at DESC);

CREATE INDEX idx_recsnapshot_candidate_rank
    ON recommendation_snapshot_candidate (snapshot_id, rank);
