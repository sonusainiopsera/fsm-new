-- V30__first_time_fix_quality.sql
-- WO-164: First-time fix rate with matured cohort linkage.
-- All changes are expand-phase only (additive, no destructive alterations).

-- ============================================================
-- 1. Fault identity on work_order
--    fault_code     : structured code from the fault catalogue, preferred key
--    fault_category : normalised category identifier, fallback key when code absent
--    fault_key derivation (app layer): fault_code if present, else UPPER(fault_category);
--    work orders where both are NULL are routed to the UNCLASSIFIABLE bucket.
-- ============================================================
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS fault_code     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS fault_category VARCHAR(100);

ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS fault_code     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS fault_category VARCHAR(100);

-- ============================================================
-- 2. Analytics closure projection
--    Populated by RepeatVisitLinker on every WORK_ORDER_TRANSITION → CLOSED event.
--    Maturity transitions: PROVISIONAL → MATURED via the daily MaturationSweepJob
--    once matured_at (= closed_at + 30 days) has passed.
--    Unique constraint on work_order_id prevents duplicate rows from event replay.
-- ============================================================
CREATE TABLE analytics_closure_projection (
    id                UUID         NOT NULL,
    work_order_id     UUID         NOT NULL,
    asset_id          UUID,
    asset_category    VARCHAR(100),
    fault_key         VARCHAR(200),
    closed_at         TIMESTAMPTZ  NOT NULL,
    maturity          VARCHAR(20)  NOT NULL DEFAULT 'PROVISIONAL',
    matured_at        TIMESTAMPTZ  NOT NULL,
    is_first_time_fix BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_analytics_closure     PRIMARY KEY (id),
    CONSTRAINT uq_analytics_closure_wo  UNIQUE (work_order_id),
    CONSTRAINT chk_analytics_closure_maturity
        CHECK (maturity IN ('PROVISIONAL', 'MATURED'))
);

-- Supports MaturationSweepJob: find PROVISIONAL rows whose window has elapsed
CREATE INDEX idx_analytics_closure_maturity_matured
    ON analytics_closure_projection (maturity, matured_at)
    WHERE maturity = 'PROVISIONAL';

-- Supports RepeatVisitLinker: find prior closures with same asset+fault
CREATE INDEX idx_analytics_closure_asset_fault
    ON analytics_closure_projection (asset_id, fault_key, closed_at)
    WHERE asset_id IS NOT NULL AND fault_key IS NOT NULL;

-- Supports time-windowed aggregation in FirstTimeFixCalculator
CREATE INDEX idx_analytics_closure_closed_at
    ON analytics_closure_projection (closed_at DESC);

-- ============================================================
-- 3. Repeat visit link
--    Records the ordered pair (earlier, later) for two work orders that visited
--    the same asset for the same fault within the 30-day window.
--    Unique constraint on (earlier, later) prevents duplicate links on replay.
--    Index on later_work_order_id enables back-reference lookup.
-- ============================================================
CREATE TABLE repeat_visit_link (
    id                    UUID         NOT NULL,
    earlier_work_order_id UUID         NOT NULL,
    later_work_order_id   UUID         NOT NULL,
    asset_id              UUID         NOT NULL,
    fault_key             VARCHAR(200) NOT NULL,
    days_between          INTEGER      NOT NULL,
    linked_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_repeat_visit_link  PRIMARY KEY (id),
    CONSTRAINT uq_repeat_visit_pair  UNIQUE (earlier_work_order_id, later_work_order_id)
);

CREATE INDEX idx_repeat_visit_link_later
    ON repeat_visit_link (later_work_order_id);
