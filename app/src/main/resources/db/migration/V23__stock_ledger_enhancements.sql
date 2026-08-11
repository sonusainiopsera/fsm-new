-- V23__stock_ledger_enhancements.sql
-- Expand-only: adds rich movement columns to stock_ledger, least-privilege grants,
-- and a no_parts_required flag on work_order for the completeness metric.

-- ============================================================
-- 0. Relax NOT NULL on legacy columns so new rich inserts can
--    omit them (location_id is superseded by from_location_id).
-- ============================================================
ALTER TABLE stock_ledger
    ALTER COLUMN location_id    DROP NOT NULL,
    ALTER COLUMN quantity_change DROP NOT NULL;

-- ============================================================
-- 1. Expand stock_ledger with full movement fields
-- ============================================================
ALTER TABLE stock_ledger
    ADD COLUMN IF NOT EXISTS from_location_id  UUID,
    ADD COLUMN IF NOT EXISTS to_location_id    UUID,
    ADD COLUMN IF NOT EXISTS movement_type     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS delta_quantity    INTEGER,
    ADD COLUMN IF NOT EXISTS resulting_quantity INTEGER,
    ADD COLUMN IF NOT EXISTS reason_code       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS work_order_id     UUID,
    ADD COLUMN IF NOT EXISTS actor_user_id     UUID,
    ADD COLUMN IF NOT EXISTS correlation_id    UUID,
    ADD COLUMN IF NOT EXISTS idempotency_key   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS occurred_at       TIMESTAMPTZ;

-- Back-fill from_location_id = location_id for pre-existing rows
UPDATE stock_ledger
SET    from_location_id = location_id,
       delta_quantity   = quantity_change,
       occurred_at      = created_at
WHERE  from_location_id IS NULL;

-- CHECK constraint on movement_type vocabulary (NOT VALID = skip existing rows, validate separately)
ALTER TABLE stock_ledger
    ADD CONSTRAINT chk_stock_ledger_movement_type
        CHECK (movement_type IN ('CONSUMPTION','RETURN','TRANSFER','ADJUSTMENT','RECEIPT'))
        NOT VALID;

ALTER TABLE stock_ledger VALIDATE CONSTRAINT chk_stock_ledger_movement_type;

-- ============================================================
-- 2. Indexes required by acceptance criteria
-- ============================================================
-- (from_location_id, occurred_at DESC) — primary query pattern
CREATE INDEX IF NOT EXISTS idx_stock_ledger_loc_occurred
    ON stock_ledger (from_location_id, occurred_at DESC);

-- (work_order_id) — completeness query and movement-by-WO filter
CREATE INDEX IF NOT EXISTS idx_stock_ledger_work_order
    ON stock_ledger (work_order_id)
    WHERE work_order_id IS NOT NULL;

-- (part_id, from_location_id) — reconciliation GROUP BY query
CREATE INDEX IF NOT EXISTS idx_stock_ledger_part_loc
    ON stock_ledger (part_id, from_location_id);

-- occurred_at general range scans (reconciliation window)
CREATE INDEX IF NOT EXISTS idx_stock_ledger_occurred_at
    ON stock_ledger (occurred_at DESC);

-- Unique filtered index on idempotency_key (only for non-null values)
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_ledger_idempotency
    ON stock_ledger (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ============================================================
-- 3. Least-privilege grants — runtime role 'fieldservice' gets
--    INSERT + SELECT on stock_ledger only; UPDATE and DELETE
--    are explicitly revoked so the database enforces append-only.
-- ============================================================
GRANT  SELECT, INSERT ON stock_ledger TO fieldservice;
REVOKE UPDATE, DELETE ON stock_ledger FROM fieldservice;

-- ============================================================
-- 4. work_order — no_parts_required flag for completeness metric
-- ============================================================
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS no_parts_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS no_parts_required BOOLEAN;
