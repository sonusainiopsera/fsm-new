-- =============================================================================
-- V32: First-time fix quality tracking (WO-164)
-- =============================================================================
-- Creates:
--   analytics_closure_projection — one row per closed/completed WO with maturity
--   repeat_visit_link            — links earlier/later WO for same asset+fault
--   Expands work_order with fault_code and fault_category for fault_key derivation
--
-- Fault-key derivation rule (documented here as the normative source):
--   1. fault_code IS NOT NULL → fault_key = upper(trim(fault_code))
--   2. fault_category IS NOT NULL → fault_key = 'CAT:' || upper(trim(fault_category))
--   3. Otherwise → work order is UNCLASSIFIABLE and excluded from FTF numerator+denominator.
--
-- First-time fix formula:
--   matured_ftf_rate = COUNT(is_first_time_fix=true, maturity='MATURED', fault_key IS NOT NULL)
--                    / COUNT(maturity='MATURED', fault_key IS NOT NULL)
--   A work order in the provisional cohort has matured_at > NOW() (window not elapsed).
--   A work order reaches MATURED exactly at matured_at = closed_at + 30 days.
--   A repeat visit at exactly 30 days after closure falls OUTSIDE the window (days_between=30
--   uses a strict less-than boundary: days_between < 30 triggers linkage).
--
-- Expand-phase only — no destructive changes to existing tables.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Expand work_order with structured fault identity columns.
-- fault_code: a fixed vocabulary code (e.g. COMPRESSOR_FAILURE, FAN_BEARING_WORN).
-- fault_category: a broader category code (e.g. MECHANICAL, ELECTRICAL).
-- Free-text fault_description (V31) is intentionally excluded from matching.
-- ---------------------------------------------------------------------------
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS fault_code     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS fault_category VARCHAR(100);

COMMENT ON COLUMN work_order.fault_code IS
    'Structured fault code used as primary fault_key source. ' ||
    'When present, fault_key = upper(trim(fault_code)). ' ||
    'Free-text fault_description is never used for repeat-visit matching.';
COMMENT ON COLUMN work_order.fault_category IS
    'Fault category code used as fallback fault_key source when fault_code is absent. ' ||
    'Fault_key becomes ''CAT:''||upper(trim(fault_category)).';

ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS fault_code     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS fault_category VARCHAR(100);

-- Index: support lookups within 30-day window for a given asset + fault_key
CREATE INDEX IF NOT EXISTS idx_work_order_asset_fault
    ON work_order (asset_id, fault_code, fault_category)
    WHERE state IN ('COMPLETED', 'CLOSED') AND asset_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- analytics_closure_projection: one row per closed/completed work order.
--
-- Maturity lifecycle:
--   PROVISIONAL — closed_at + 30 days has NOT yet elapsed; excluded from matured rate.
--   MATURED     — closed_at + 30 days has elapsed; eligible for matured rate computation.
--
-- is_first_time_fix starts true at creation; set false when a repeat_visit_link row
-- identifies this work order as the earlier predecessor (same asset, same fault, within 30 days).
--
-- fault_key NULL → UNCLASSIFIABLE (missing asset or fault identity);
-- excluded from both numerator and denominator of the FTF rate.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS analytics_closure_projection (
    id                  UUID         NOT NULL PRIMARY KEY,
    work_order_id       UUID         NOT NULL,
    asset_id            UUID,
    fault_key           VARCHAR(200),        -- NULL means UNCLASSIFIABLE
    is_first_time_fix   BOOLEAN      NOT NULL DEFAULT true,
    maturity            VARCHAR(20)  NOT NULL DEFAULT 'PROVISIONAL',
    closed_at           TIMESTAMPTZ  NOT NULL,
    matured_at          TIMESTAMPTZ  NOT NULL,   -- closed_at + 30 days
    linked_at           TIMESTAMPTZ,             -- set when repeat_visit_link created
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_closure_projection_work_order UNIQUE (work_order_id),
    CONSTRAINT chk_closure_maturity CHECK (maturity IN ('PROVISIONAL', 'MATURED')),
    -- Unclassifiable work orders must not be marked first-time-fix
    CONSTRAINT chk_closure_unclassifiable
        CHECK (fault_key IS NOT NULL OR is_first_time_fix = false)
);

-- Performance: maturation sweep queries rows approaching or past matured_at
CREATE INDEX IF NOT EXISTS idx_closure_matured_at
    ON analytics_closure_projection (matured_at)
    WHERE maturity = 'PROVISIONAL';

-- Back-reference: find all closure records for an asset+fault combination
CREATE INDEX IF NOT EXISTS idx_closure_asset_fault_key
    ON analytics_closure_projection (asset_id, fault_key, closed_at DESC)
    WHERE fault_key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- repeat_visit_link: records that the later work order is a repeat visit.
--
-- Ordered pair (earlier_work_order_id, later_work_order_id): the unique constraint
-- ensures idempotent replay cannot create duplicate links. The pair is ordered by
-- closure date: earlier_work_order_id closed first.
--
-- days_between is stored as an integer (<30 by construction; exactly 30 is OUTSIDE the window).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS repeat_visit_link (
    id                      UUID         NOT NULL PRIMARY KEY,
    earlier_work_order_id   UUID         NOT NULL,
    later_work_order_id     UUID         NOT NULL,
    asset_id                UUID         NOT NULL,
    fault_key               VARCHAR(200) NOT NULL,
    days_between            INTEGER      NOT NULL,
    linked_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_repeat_visit_link UNIQUE (earlier_work_order_id, later_work_order_id),
    CONSTRAINT chk_repeat_visit_days CHECK (days_between >= 0 AND days_between < 30)
);

-- Support back-reference lookup: given a work order ID, find its predecessor link
CREATE INDEX IF NOT EXISTS idx_repeat_visit_later
    ON repeat_visit_link (later_work_order_id);

-- Support forward lookup: given a work order, find all its successors
CREATE INDEX IF NOT EXISTS idx_repeat_visit_earlier
    ON repeat_visit_link (earlier_work_order_id);

-- ---------------------------------------------------------------------------
-- Grants: analytics worker needs read/write to both new tables.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') THEN
        GRANT SELECT, INSERT, UPDATE ON TABLE analytics_closure_projection TO fieldservice;
        GRANT SELECT, INSERT ON TABLE repeat_visit_link TO fieldservice;
    END IF;
END
$$;
