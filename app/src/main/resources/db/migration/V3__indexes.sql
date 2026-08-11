-- V3__indexes.sql
-- Additional indexes for P0 read paths not already covered by V1.
-- Core structural indexes are co-located with table DDL in V1 for clarity.
-- This migration adds covering and composite indexes required by the
-- dispatch scoring, SLA sweep, and customer portal read paths.

-- work_order: composite for the dispatcher board keyset pagination.
-- The DESC on created_at supports the common "newest first" sort.
-- Already exists in V1 as idx_work_order_created_at_id — no duplicate.

-- work_order: partial index for the SLA evaluator sweep (only active orders).
CREATE INDEX IF NOT EXISTS idx_work_order_sla_sweep
    ON work_order(sla_deadline ASC, priority, id)
    WHERE state NOT IN ('COMPLETED', 'CLOSED', 'CANCELLED')
      AND sla_deadline IS NOT NULL;

-- technician_certification: composite for the dispatch feasibility gate.
-- Covers: "find non-expired, non-revoked certifications for this technician of this type".
CREATE INDEX IF NOT EXISTS idx_technician_cert_type_expiry
    ON technician_certification(technician_id, cert_type, expires_at)
    WHERE is_revoked = false;

-- technician: active technicians for dispatch candidate pool.
CREATE INDEX IF NOT EXISTS idx_technician_active
    ON technician(id)
    WHERE is_active = true;

-- assignment: current assignments for a technician (workload view).
CREATE INDEX IF NOT EXISTS idx_assignment_technician_current
    ON assignment(technician_id, work_order_id)
    WHERE is_current = true;

-- stock_balance: lookup by location (technician's van inventory).
CREATE INDEX IF NOT EXISTS idx_stock_balance_location_part
    ON stock_balance(location_id, part_id);

-- stock_ledger: time-ordered history per part (audit and analytics).
-- Already covered by idx_stock_ledger_part_created in V1.

-- app_user: active users lookup.
CREATE INDEX IF NOT EXISTS idx_app_user_email_active
    ON app_user(email)
    WHERE is_active = true;

-- sla_policy: active policy lookup by priority.
CREATE INDEX IF NOT EXISTS idx_sla_policy_priority_active
    ON sla_policy(priority, effective_from DESC)
    WHERE effective_to IS NULL;
