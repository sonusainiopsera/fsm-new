-- V22__sla_enhancements.sql
-- SLA policy administration, clock-pause ledger, and deadline fields.
-- Expand-only: all changes are additive.

-- ============================================================
-- 1. Add administrative columns to sla_policy
-- ============================================================
ALTER TABLE sla_policy
    ADD COLUMN IF NOT EXISTS active  BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

-- ============================================================
-- 2. Add missing CHECK constraints to sla_policy
--    (V2 had at_risk_fraction > 0 AND < 1; WO-142 requires 0.50 ≤ x ≤ 1.00)
--    (V2 lacked resolution_minutes >= response_minutes check)
-- ============================================================
ALTER TABLE sla_policy
    DROP CONSTRAINT IF EXISTS chk_at_risk_fraction,
    ADD  CONSTRAINT chk_at_risk_fraction
         CHECK (at_risk_fraction >= 0.50 AND at_risk_fraction <= 1.00);

ALTER TABLE sla_policy
    ADD CONSTRAINT IF NOT EXISTS chk_sla_resolution_ge_response
        CHECK (resolution_minutes >= response_minutes);

ALTER TABLE sla_policy
    ADD CONSTRAINT IF NOT EXISTS chk_response_minutes_positive
        CHECK (response_minutes > 0);

-- Index for time-versioned policy lookup (priority + effective_from DESC)
CREATE INDEX IF NOT EXISTS idx_sla_policy_priority_from
    ON sla_policy (priority, effective_from DESC);

-- ============================================================
-- 3. sla_policy_aud — Envers audit table for SlaPolicy
-- ============================================================
CREATE TABLE IF NOT EXISTS sla_policy_aud (
    id                 UUID         NOT NULL,
    REV                INTEGER      NOT NULL,
    REVTYPE            SMALLINT,
    priority           VARCHAR(20),
    response_minutes   INTEGER,
    resolution_minutes INTEGER,
    at_risk_fraction   NUMERIC(3,2),
    effective_from     TIMESTAMPTZ,
    effective_to       TIMESTAMPTZ,
    active             BOOLEAN,
    created_at         TIMESTAMPTZ,
    version            INTEGER,
    CONSTRAINT pk_sla_policy_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_sla_policy_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX IF NOT EXISTS brin_sla_policy_aud_rev ON sla_policy_aud USING BRIN (REV);

-- ============================================================
-- 4. sla_clock_pause — SLA clock pause ledger
--    append-only: resumed_at filled on exit from ON_HOLD
-- ============================================================
CREATE TABLE IF NOT EXISTS sla_clock_pause (
    id            UUID        NOT NULL,
    work_order_id UUID        NOT NULL,
    hold_reason   VARCHAR(50) NOT NULL,
    paused_at     TIMESTAMPTZ NOT NULL,
    resumed_at    TIMESTAMPTZ,
    CONSTRAINT pk_sla_clock_pause    PRIMARY KEY (id),
    CONSTRAINT fk_sla_clock_pause_wo FOREIGN KEY (work_order_id) REFERENCES work_order (id)
);

CREATE INDEX IF NOT EXISTS idx_sla_clock_pause_wo ON sla_clock_pause (work_order_id);

-- Prevents more than one open pause row per work order
CREATE UNIQUE INDEX IF NOT EXISTS uq_sla_clock_pause_open
    ON sla_clock_pause (work_order_id)
    WHERE resumed_at IS NULL;

-- ============================================================
-- 5. hold_reason — add pauses_sla_clock flag
-- ============================================================
ALTER TABLE hold_reason
    ADD COLUMN IF NOT EXISTS pauses_sla_clock BOOLEAN NOT NULL DEFAULT FALSE;

-- Update existing hold reasons with appropriate clock-pausing semantics:
-- Customer/site-caused delays pause the SLA clock; internal delays do not.
UPDATE hold_reason SET pauses_sla_clock = TRUE  WHERE code = 'CUSTOMER_UNAVAILABLE';
UPDATE hold_reason SET pauses_sla_clock = TRUE  WHERE code = 'ACCESS_DENIED';
UPDATE hold_reason SET pauses_sla_clock = TRUE  WHERE code = 'WEATHER';
UPDATE hold_reason SET pauses_sla_clock = TRUE  WHERE code = 'SAFETY_CONCERN';
UPDATE hold_reason SET pauses_sla_clock = TRUE  WHERE code = 'AWAITING_APPROVAL';
UPDATE hold_reason SET pauses_sla_clock = FALSE WHERE code = 'AWAITING_PARTS';
UPDATE hold_reason SET pauses_sla_clock = FALSE WHERE code = 'LEGACY_OTHER';

-- ============================================================
-- 6. work_order — add at_risk_at column for the risk threshold instant
-- ============================================================
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS at_risk_at TIMESTAMPTZ;

ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS at_risk_at TIMESTAMPTZ;
