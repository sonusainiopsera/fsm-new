-- =============================================================================
-- V26: Expand stock_ledger with full audit columns and append-only enforcement (WO-150)
-- Expand-only: adds columns; existing rows and constraints are not removed.
-- =============================================================================

-- New columns for full movement audit trail
ALTER TABLE stock_ledger
    ADD COLUMN IF NOT EXISTS from_location_id  UUID,
    ADD COLUMN IF NOT EXISTS to_location_id    UUID,
    ADD COLUMN IF NOT EXISTS resulting_quantity INTEGER,
    ADD COLUMN IF NOT EXISTS actor_user_id      UUID,
    ADD COLUMN IF NOT EXISTS correlation_id     UUID,
    ADD COLUMN IF NOT EXISTS idempotency_key    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS occurred_at        TIMESTAMPTZ;

-- Backfill from_location_id from the existing location_id column
UPDATE stock_ledger SET from_location_id = location_id WHERE from_location_id IS NULL;

-- Backfill occurred_at from created_at for historical rows
UPDATE stock_ledger SET occurred_at = created_at WHERE occurred_at IS NULL;

-- Unique partial index: idempotency_key is only checked when present
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_ledger_idempotency_key
    ON stock_ledger (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Composite index for time-range queries per location (AC-1)
CREATE INDEX IF NOT EXISTS idx_stock_ledger_location_occurred_at
    ON stock_ledger (location_id, occurred_at DESC);

-- Composite index for reconciliation query: SUM(delta) per (part, location)
CREATE INDEX IF NOT EXISTS idx_stock_ledger_part_location
    ON stock_ledger (part_id, location_id);

-- =============================================================================
-- Append-only enforcement: REVOKE UPDATE and DELETE on stock_ledger
-- from the runtime application role so the DB rejects any mutation attempt.
-- The fieldservice role was created in V6 and granted in V12.
-- =============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') THEN
        REVOKE UPDATE, DELETE ON TABLE stock_ledger FROM fieldservice;
    END IF;
END
$$;

-- =============================================================================
-- work_order.no_parts_required: explicit marker that a work order required
-- no parts consumption. Treated as "complete" for the parts-logging-completeness
-- metric so absence of work_order_part records is not a false gap.
-- =============================================================================
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS no_parts_required BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS no_parts_required BOOLEAN;
