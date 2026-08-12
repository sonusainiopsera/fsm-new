-- WO-136: immutable recommendation snapshots for dispatch audit trail
-- These tables are insert-only; no UPDATE or DELETE is issued by the application.

CREATE TABLE recommendation_snapshot (
    id                      UUID         NOT NULL PRIMARY KEY,
    work_order_id           UUID         NOT NULL REFERENCES work_order(id),
    generated_at            TIMESTAMPTZ  NOT NULL,
    generated_by            UUID,
    weight_set_version      TEXT,
    travel_estimate_degraded BOOLEAN     NOT NULL DEFAULT FALSE,
    parts_data_degraded     BOOLEAN      NOT NULL DEFAULT FALSE,
    candidate_pool_size     INTEGER      NOT NULL DEFAULT 0,
    truncated               BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE recommendation_snapshot_candidate (
    id               UUID          NOT NULL PRIMARY KEY,
    snapshot_id      UUID          NOT NULL REFERENCES recommendation_snapshot(id) ON DELETE RESTRICT,
    technician_id    UUID          NOT NULL,
    rank             INTEGER       NOT NULL,
    score            NUMERIC(7,6)  NOT NULL,
    factor_breakdown JSONB         NOT NULL
);

-- Support "give me all recommendations for this work order, newest first"
CREATE INDEX idx_rec_snap_work_order_time
    ON recommendation_snapshot (work_order_id, generated_at DESC);

-- Support "give me all candidates for this snapshot, in rank order"
CREATE INDEX idx_rec_snap_cand_snapshot_rank
    ON recommendation_snapshot_candidate (snapshot_id, rank);
