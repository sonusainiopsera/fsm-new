-- =============================================================================
-- V40: SLA risk flag table and supporting indexes (WO-143)
-- Additive-only: new table and indexes; existing schema untouched.
--
-- sla_risk_flag records at-risk and projected-overrun detections per work order.
-- The partial unique index on (work_order_id, flag_type) WHERE cleared_at IS NULL
-- makes concurrent or repeated inserts for the same open flag a no-op (idempotency).
-- =============================================================================

CREATE TABLE IF NOT EXISTS sla_risk_flag (
    id                  UUID         NOT NULL PRIMARY KEY,
    work_order_id       UUID         NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    flag_type           VARCHAR(20)  NOT NULL,
    trigger_reason      VARCHAR(100) NOT NULL,
    projection_basis    TEXT,
    minutes_remaining   INTEGER,
    raised_at           TIMESTAMPTZ  NOT NULL,
    cleared_at          TIMESTAMPTZ,
    clear_reason        VARCHAR(100),
    created_by_system   BOOLEAN      NOT NULL DEFAULT TRUE,
    version             INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT chk_sla_risk_flag_type
        CHECK (flag_type IN ('AT_RISK', 'PROJECTED_OVERRUN')),
    CONSTRAINT chk_sla_risk_flag_cleared_after_raised
        CHECK (cleared_at IS NULL OR cleared_at >= raised_at)
);

-- Partial unique index: exactly one open flag per work order per flag type.
-- ON CONFLICT on this index provides idempotent insert semantics.
CREATE UNIQUE INDEX IF NOT EXISTS uq_sla_risk_flag_open
    ON sla_risk_flag (work_order_id, flag_type)
    WHERE cleared_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sla_risk_flag_work_order_id
    ON sla_risk_flag (work_order_id);

-- Supporting index for the sweep candidate query.
-- The partial predicate keeps the index small — terminal-state rows are excluded.
CREATE INDEX IF NOT EXISTS idx_work_order_sweep_candidate
    ON work_order (state, resolution_due_at)
    WHERE state NOT IN ('COMPLETED', 'CLOSED', 'CANCELLED');
