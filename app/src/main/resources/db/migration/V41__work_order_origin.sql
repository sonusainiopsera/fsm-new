-- V41__work_order_origin.sql
-- Expand-only migration: adds work_order.origin for request-origin attribution (WO-170, O4).
--
-- Vocabulary: PORTAL, DISPATCHER, FRONT_OFFICE
-- Default is DISPATCHER so all existing rows get a sensible value without a backfill.
-- Mirrors into work_order_aud for Envers revision history.
-- Index on (origin, created_at) supports O4 portal adoption reporting queries.

ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS origin VARCHAR(20) NOT NULL DEFAULT 'DISPATCHER';

ALTER TABLE work_order
    ADD CONSTRAINT chk_work_order_origin
        CHECK (origin IN ('PORTAL', 'DISPATCHER', 'FRONT_OFFICE'));

-- Mirror in Envers audit table
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS origin VARCHAR(20);

-- Reporting index: portal adoption by day/week
CREATE INDEX IF NOT EXISTS idx_work_order_origin_created_at
    ON work_order (origin, created_at DESC);
