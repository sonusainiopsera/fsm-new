-- =============================================================================
-- V25: SLA deadline columns on work_order (WO-142)
-- Expand-only: nullable columns for rolling deploy; backfill is a separate step.
-- response_due_at   — first technician response must occur by this instant
-- resolution_due_at — work order must be resolved by this instant
-- at_risk_at        — work order enters at-risk zone at this instant
-- All three are stamped atomically with domain row creation; NULL for pre-WO-142 rows.
-- =============================================================================

ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS response_due_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolution_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS at_risk_at        TIMESTAMPTZ;

-- Mirror the new columns into the Envers audit table so deadlines are captured
-- in every revision that Hibernate Envers creates when work_order is modified.
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS response_due_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolution_due_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS at_risk_at        TIMESTAMPTZ;
