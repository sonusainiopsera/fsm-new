-- V46__technician_day_index.sql
-- Supporting schema for the technician today's-jobs endpoint (WO-154).
-- All changes are additive — no column drops or renames.

-- ============================================================
-- 1. Scheduled window columns on work_order
-- ============================================================
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS scheduled_window_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scheduled_window_end   TIMESTAMPTZ;

-- Mirror on Envers audit table
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS scheduled_window_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scheduled_window_end   TIMESTAMPTZ;

-- ============================================================
-- 2. Site contact phone
--    Distinct from customer.primary_contact_phone — the site may
--    have a different on-site contact number for the attending technician.
-- ============================================================
ALTER TABLE site
    ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(50);

-- ============================================================
-- 3. Covering index for the technician day query
--    Query plan: index scan on (assigned_technician_id, scheduled_window_start),
--    state returned from INCLUDE without touching the heap.
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_work_order_assignee_schedule
    ON work_order (assigned_technician_id, scheduled_window_start)
    INCLUDE (state);
