-- =============================================================================
-- V24: pauses_sla_clock on hold_reason; sla_clock_pause ledger (WO-142)
-- Expand-only: new column and new table; existing rows and constraints untouched.
-- =============================================================================

-- Flag on hold_reason vocabulary: whether a hold of this type pauses the SLA clock.
ALTER TABLE hold_reason
    ADD COLUMN IF NOT EXISTS pauses_sla_clock BOOLEAN NOT NULL DEFAULT false;

-- Seed: hold reasons that are legitimately customer/ops-caused delay pause the clock.
-- AWAITING_PARTS and ACCESS_DENIED are provider delays — clock keeps running.
UPDATE hold_reason SET pauses_sla_clock = true
WHERE code IN ('CUSTOMER_UNAVAILABLE', 'AWAITING_APPROVAL');

-- =============================================================================
-- sla_clock_pause: append-only ledger of SLA clock suspension intervals.
-- One row per hold interval where pauses_sla_clock=true.
-- resumed_at is NULL while the pause is still open (work order still on hold).
-- Partial unique index: at most one open pause row per work order at a time.
-- =============================================================================
CREATE TABLE IF NOT EXISTS sla_clock_pause (
    id               UUID          NOT NULL PRIMARY KEY,
    work_order_id    UUID          NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    hold_reason_code VARCHAR(100)  NOT NULL,
    paused_at        TIMESTAMPTZ   NOT NULL,
    resumed_at       TIMESTAMPTZ,

    CONSTRAINT chk_sla_clock_pause_resumed_after_paused
        CHECK (resumed_at IS NULL OR resumed_at >= paused_at)
);

-- Partial unique index: at most one open pause per work order
CREATE UNIQUE INDEX IF NOT EXISTS uq_sla_clock_pause_open
    ON sla_clock_pause (work_order_id)
    WHERE resumed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sla_clock_pause_work_order_id
    ON sla_clock_pause (work_order_id);
