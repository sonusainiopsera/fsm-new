-- V48: Add scheduled window columns to work_order and create the technician day query index.
-- Additive only — no column drops, no type changes.

ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS scheduled_window_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scheduled_window_end   TIMESTAMPTZ;

-- Envers audit table must mirror the main table columns
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS scheduled_window_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scheduled_window_end   TIMESTAMPTZ;

-- Covering index for the technician day query:
--   (assignee_id, scheduled_start) with state INCLUDE avoids a heap fetch for the carry-over
--   state filter when scanning a narrow assignee slice.
CREATE INDEX IF NOT EXISTS idx_wo_assignee_schedule
    ON work_order (assigned_technician_id, scheduled_window_start)
    INCLUDE (state);
