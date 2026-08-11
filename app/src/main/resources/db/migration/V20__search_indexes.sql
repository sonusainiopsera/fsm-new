-- =============================================================================
-- V20: Composite indexes for work order search endpoint (WO-127)
-- Expand-only: adds indexes only; no tables or columns are modified.
-- =============================================================================

-- Supports state + deadline range queries and sort-stable traversal
CREATE INDEX IF NOT EXISTS idx_wo_state_deadline_id
    ON work_order(state, sla_deadline, id);

-- Supports technician-scoped board queries (scope predicate + state filter)
CREATE INDEX IF NOT EXISTS idx_wo_technician_state_id
    ON work_order(assigned_technician_id, state, id);

-- Supports customer-scoped board queries with created-at DESC ordering
CREATE INDEX IF NOT EXISTS idx_wo_customer_created_id
    ON work_order(customer_id, created_at DESC, id);

-- Partial index for at-risk work orders (open + past deadline)
-- Keeps the index small; used when atRisk=true filter is applied
CREATE INDEX IF NOT EXISTS idx_wo_at_risk_partial
    ON work_order(sla_deadline, id)
    WHERE state NOT IN ('COMPLETED', 'CLOSED', 'CANCELLED')
      AND sla_deadline IS NOT NULL;

-- Dispatcher board: all work orders sorted by created_at (default sort)
CREATE INDEX IF NOT EXISTS idx_wo_created_at_id
    ON work_order(created_at DESC, id ASC);
