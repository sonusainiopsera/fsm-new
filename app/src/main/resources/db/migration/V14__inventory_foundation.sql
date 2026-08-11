-- =============================================================================
-- V14: Inventory foundation — parts catalog, stock locations, stock balances
-- =============================================================================
-- Expand-only: adds columns, constraints, and indexes to the pre-existing
-- part, stock_location, and stock_balance tables created in V1.
-- Creates Envers AUD tables for part and stock_location.
-- stock_balance is NOT audited; the WO-054 append-only ledger is its audit
-- mechanism (see ADR-0012: stock-balance-audit-mechanism).
-- =============================================================================

-- =============================================================================
-- 1. Extend part table with full catalog columns
-- =============================================================================

-- Add part_number as the canonical business identifier
ALTER TABLE part
    ADD COLUMN IF NOT EXISTS part_number     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS description     TEXT,
    ADD COLUMN IF NOT EXISTS unit_of_measure VARCHAR(50)  NOT NULL DEFAULT 'EACH',
    ADD COLUMN IF NOT EXISTS reorder_point   INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reorder_quantity INTEGER     NOT NULL DEFAULT 0;

-- Back-fill part_number from existing sku where not yet set
UPDATE part SET part_number = sku WHERE part_number IS NULL;

-- Now make part_number NOT NULL (all rows have a value after back-fill)
ALTER TABLE part ALTER COLUMN part_number SET NOT NULL;

-- Unique index on part_number (business key)
CREATE UNIQUE INDEX IF NOT EXISTS uq_part_part_number ON part(part_number);

-- Non-negative CHECKs on reorder columns
-- Named per the platform constraint naming convention: chk_<table>_<column>
ALTER TABLE part
    ADD CONSTRAINT chk_part_reorder_point_non_negative
        CHECK (reorder_point >= 0),
    ADD CONSTRAINT chk_part_reorder_quantity_non_negative
        CHECK (reorder_quantity >= 0);

-- =============================================================================
-- 2. Extend stock_location with WAREHOUSE / VEHICLE type support
-- =============================================================================

-- Add updated_at for Envers compatibility
ALTER TABLE stock_location
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Drop the old location_type check so we can replace it with the correct vocabulary.
-- This is safe (expand-only) because the new constraint is a superset of the old one.
ALTER TABLE stock_location
    DROP CONSTRAINT IF EXISTS chk_stock_location_type;

-- New constraint: WAREHOUSE and VEHICLE are the canonical types (VAN retained for
-- backward compatibility with any pre-existing VAN rows from V1 seed data).
ALTER TABLE stock_location
    ADD CONSTRAINT chk_stock_location_type
        CHECK (location_type IN ('WAREHOUSE', 'VEHICLE', 'VAN', 'SITE'));

-- Technician-consistency rule:
-- VEHICLE locations must have an owning technician; WAREHOUSE locations must not.
-- VAN and SITE are legacy types exempted from this rule.
ALTER TABLE stock_location
    ADD CONSTRAINT chk_stock_location_technician_consistency
        CHECK (
            (location_type = 'VEHICLE' AND technician_id IS NOT NULL)
            OR (location_type = 'WAREHOUSE' AND technician_id IS NULL)
            OR location_type IN ('VAN', 'SITE')
        );

-- =============================================================================
-- 3. Extend stock_balance with quantity_reserved and created_at
-- =============================================================================

ALTER TABLE stock_balance
    ADD COLUMN IF NOT EXISTS quantity_reserved INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Non-negative CHECK on quantity_reserved
ALTER TABLE stock_balance
    ADD CONSTRAINT chk_stock_balance_reserved_non_negative
        CHECK (quantity_reserved >= 0);

-- Rename the existing non-negative constraint to the canonical name
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'stock_balance'
          AND constraint_name = 'chk_stock_balance_non_negative'
    ) THEN
        ALTER TABLE stock_balance
            RENAME CONSTRAINT chk_stock_balance_non_negative TO stock_non_negative;
    END IF;
END
$$;

-- Ensure stock_non_negative exists even if rename was not needed
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'stock_balance'
          AND constraint_name = 'stock_non_negative'
    ) THEN
        ALTER TABLE stock_balance
            ADD CONSTRAINT stock_non_negative CHECK (quantity_on_hand >= 0);
    END IF;
END
$$;

-- =============================================================================
-- 4. Envers AUD tables for part and stock_location
-- =============================================================================
-- REVINFO and revinfo_seq already exist from V5.
-- stock_balance is deliberately NOT audited; the WO-054 append-only ledger
-- (stock_ledger) serves as its immutable audit trail.

-- part_aud
CREATE TABLE IF NOT EXISTS part_aud (
    id                UUID         NOT NULL,
    rev               INTEGER      NOT NULL,
    revtype           SMALLINT     NOT NULL,
    part_number       VARCHAR(100),
    sku               VARCHAR(100),
    name              VARCHAR(255),
    description       TEXT,
    unit              VARCHAR(50),
    unit_of_measure   VARCHAR(50),
    reorder_point     INTEGER,
    reorder_quantity  INTEGER,
    is_active         BOOLEAN,
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    CONSTRAINT pk_part_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_part_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX IF NOT EXISTS idx_part_aud_rev_brin
    ON part_aud USING brin (rev);

-- stock_location_aud
CREATE TABLE IF NOT EXISTS stock_location_aud (
    id            UUID          NOT NULL,
    rev           INTEGER       NOT NULL,
    revtype       SMALLINT      NOT NULL,
    technician_id UUID,
    name          VARCHAR(255),
    location_type VARCHAR(50),
    is_active     BOOLEAN,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    CONSTRAINT pk_stock_location_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_stock_location_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX IF NOT EXISTS idx_stock_location_aud_rev_brin
    ON stock_location_aud USING brin (rev);

-- =============================================================================
-- 5. Additional indexes for the dispatch availability path (AC6)
-- =============================================================================

-- Composite index for availability lookup: "what quantity of part P is at location L?"
CREATE INDEX IF NOT EXISTS idx_stock_balance_part_loc
    ON stock_balance(part_id, location_id);

-- Separate index for "all balances at a given location" (technician scan)
-- idx_stock_balance_location_id already exists from V1; no-op here.

COMMENT ON COLUMN stock_balance.quantity_reserved IS
    'Reserved quantity — column exists for future use; no application code may set this in this release (WO-148).';

COMMENT ON TABLE stock_balance IS
    'Current quantity-on-hand per part per location. Not Envers-audited; the stock_ledger append-only table is the audit mechanism (ADR-0012).';

COMMENT ON TABLE part IS
    'Parts catalog. part_number is the canonical business key (replaces sku for new code). Envers-audited via part_aud.';

COMMENT ON TABLE stock_location IS
    'Named stock locations: WAREHOUSE (no technician) or VEHICLE (owned by a technician). Envers-audited via stock_location_aud.';
