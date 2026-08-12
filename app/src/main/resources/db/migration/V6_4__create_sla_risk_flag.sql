-- WO-143: SLA risk flag table with idempotent partial unique index

CREATE TABLE sla_risk_flag (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    work_order_id       UUID         NOT NULL,
    flag_type           VARCHAR(30)  NOT NULL,
    trigger_reason      VARCHAR(100) NOT NULL,
    projection_basis    TEXT,
    minutes_remaining   INTEGER,
    raised_at           TIMESTAMPTZ  NOT NULL,
    cleared_at          TIMESTAMPTZ,
    clear_reason        VARCHAR(100),
    created_by_system   BOOLEAN      NOT NULL DEFAULT TRUE,
    version             INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_sla_risk_flag PRIMARY KEY (id)
);

-- Partial unique index: only one open flag per (work_order, flag_type) pair.
-- ON CONFLICT DO NOTHING on this index makes repeated inserts a no-op.
CREATE UNIQUE INDEX uq_sla_risk_flag_open
    ON sla_risk_flag (work_order_id, flag_type)
    WHERE cleared_at IS NULL;

-- Candidate query index: filters open work orders ordered by resolution_deadline.
CREATE INDEX IF NOT EXISTS idx_work_order_state_resolution
    ON work_order (state, resolution_deadline)
    WHERE state IN ('NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD');
