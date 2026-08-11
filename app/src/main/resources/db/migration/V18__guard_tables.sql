-- =============================================================================
-- V18: Guard backing tables for business precondition guards (WO-125)
-- Expand-only: adds new tables; existing tables and constraints are not modified.
-- =============================================================================

-- =============================================================================
-- labour_time_record: time entries logged against a work order.
-- The LabourTimeRecordedGuard requires at least one row before permitting COMPLETE.
-- =============================================================================
CREATE TABLE labour_time_record (
    id              UUID          NOT NULL PRIMARY KEY,
    work_order_id   UUID          NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    technician_id   UUID          NOT NULL REFERENCES technician(id),
    minutes         INTEGER       NOT NULL,
    work_date       TIMESTAMPTZ   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_labour_time_minutes_positive CHECK (minutes > 0)
);

CREATE INDEX idx_labour_time_record_work_order_id ON labour_time_record(work_order_id);

-- =============================================================================
-- work_order_competency: required certification types for a work order.
-- The CertificationCurrencyGuard checks each row before permitting ASSIGN.
-- A work order with no rows passes the guard (no requirements, no gate).
-- =============================================================================
CREATE TABLE work_order_competency (
    id               UUID          NOT NULL PRIMARY KEY,
    work_order_id    UUID          NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    competency_code  VARCHAR(100)  NOT NULL,

    CONSTRAINT uq_work_order_competency UNIQUE (work_order_id, competency_code)
);

CREATE INDEX idx_work_order_competency_work_order_id ON work_order_competency(work_order_id);

-- =============================================================================
-- work_order_part_consumption: parts consumed on a work order.
-- The PartsReconciledGuard checks for unreconciled rows before permitting CLOSE.
-- A work order with no rows is considered fully reconciled.
-- =============================================================================
CREATE TABLE work_order_part_consumption (
    id              UUID          NOT NULL PRIMARY KEY,
    work_order_id   UUID          NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    part_id         UUID          NOT NULL REFERENCES part(id),
    quantity        INTEGER       NOT NULL,
    is_reconciled   BOOLEAN       NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_consumption_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_work_order_part_consumption_work_order_id
    ON work_order_part_consumption(work_order_id);
CREATE INDEX idx_work_order_part_consumption_unreconciled
    ON work_order_part_consumption(work_order_id)
    WHERE is_reconciled = false;
