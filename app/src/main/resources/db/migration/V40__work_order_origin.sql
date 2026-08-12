-- WO-170: Expand-only origin attribution for work orders.
-- Existing rows receive 'DISPATCHER' as the default; no backfill needed.

ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS origin VARCHAR(30) NOT NULL DEFAULT 'DISPATCHER'
        CONSTRAINT ck_work_order_origin CHECK (origin IN ('PORTAL', 'DISPATCHER', 'FRONT_OFFICE'));

-- Mirror origin in the Envers audit table so revisions carry the attribution value.
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS origin VARCHAR(30);

-- O4 adoption reporting: count portal submissions per day.
CREATE INDEX IF NOT EXISTS idx_work_order_origin_created
    ON work_order (origin, created_at);
