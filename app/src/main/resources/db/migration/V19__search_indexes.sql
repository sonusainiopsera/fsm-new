-- V19__search_indexes.sql
-- Expand-phase migration: adds deadline/at-risk columns required by the board search
-- feature and creates composite indexes to support board queries at scale.

-- ============================================================
-- work_order — deadline and at-risk columns for filtering/sorting
-- response_deadline  : SLA response clock deadline, set at creation time
-- resolution_deadline: SLA resolution clock deadline, set at creation time
-- at_risk            : true when elapsed time exceeds sla_policy.at_risk_fraction
-- ============================================================
ALTER TABLE work_order ADD COLUMN response_deadline   TIMESTAMPTZ;
ALTER TABLE work_order ADD COLUMN resolution_deadline TIMESTAMPTZ;
ALTER TABLE work_order ADD COLUMN at_risk             BOOLEAN NOT NULL DEFAULT FALSE;

-- Mirror new columns in the Envers audit shadow table
ALTER TABLE work_order_aud ADD COLUMN response_deadline   TIMESTAMPTZ;
ALTER TABLE work_order_aud ADD COLUMN resolution_deadline TIMESTAMPTZ;
ALTER TABLE work_order_aud ADD COLUMN at_risk             BOOLEAN;

-- ============================================================
-- Composite indexes for board queries
-- ============================================================

-- Board sorted by state + deadline (dispatcher board, SLA view)
CREATE INDEX idx_wo_state_deadline ON work_order (state, resolution_deadline, id);

-- Technician job list: own assignments by state
CREATE INDEX idx_wo_tech_state ON work_order (assigned_technician_id, state, id);

-- Per-site timeline (also used by customer board via site FK join)
CREATE INDEX idx_wo_site_created ON work_order (site_id, created_at DESC, id);

-- Partial index for at-risk open work orders (most critical board filter)
CREATE INDEX idx_wo_at_risk_open ON work_order (resolution_deadline, id)
    WHERE at_risk = TRUE
      AND state NOT IN ('CLOSED', 'CANCELLED', 'COMPLETED');
