-- V67: Reassignment supersede model — WO-139
--
-- Adds supersede, reason, and appointment-impact columns to assignment and assignment_aud.
-- Adds appointment_confirmed flag to work_order for appointment-pinning guard.
-- Adjusts the active-assignment unique index from is_current=true to end_at IS NULL.
--
-- Expand-only: all new columns are nullable; existing rows are unaffected.
-- =============================================================================

-- ── work_order — appointment confirmation flag ──────────────────────────────

ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS appointment_confirmed BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN work_order.appointment_confirmed IS
    'True when the dispatcher has confirmed the scheduled_window_start/end with the customer.
     A confirmed appointment is a pinned constraint: reassignment requires explicit acknowledgement.';

ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS appointment_confirmed BOOLEAN;

-- ── assignment — supersede model ─────────────────────────────────────────────

ALTER TABLE assignment
    ADD COLUMN IF NOT EXISTS end_at               TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS superseded_by        UUID REFERENCES assignment(id),
    ADD COLUMN IF NOT EXISTS reassignment_reason  TEXT
        CONSTRAINT assignment_reassignment_reason_check
        CHECK (reassignment_reason IS NULL OR reassignment_reason IN (
            'TECHNICIAN_UNAVAILABLE', 'JOB_OVERRUN', 'SKILL_MISMATCH',
            'SLA_RISK', 'CUSTOMER_REQUEST', 'PARTS_UNAVAILABLE', 'OTHER'
        )),
    ADD COLUMN IF NOT EXISTS reason_notes              TEXT,
    ADD COLUMN IF NOT EXISTS appointment_impact_reason TEXT;

COMMENT ON COLUMN assignment.end_at IS
    'Set when this assignment is superseded by a subsequent assignment.
     The active assignment for a work order is the row where end_at IS NULL.';
COMMENT ON COLUMN assignment.superseded_by IS
    'ID of the assignment that replaced this one; null on the current active assignment.';
COMMENT ON COLUMN assignment.reassignment_reason IS
    'Controlled reassignment reason code (mandatory on reassignment, null on initial assignment).';
COMMENT ON COLUMN assignment.reason_notes IS
    'Optional dispatcher free-text notes supplementing the controlled reason.';
COMMENT ON COLUMN assignment.appointment_impact_reason IS
    'Recorded acknowledgement when the reassignment breaches a confirmed appointment window.
     Populated only when appointmentImpactAcknowledgement was supplied and persisted.';

-- ── Active-assignment unique constraint ──────────────────────────────────────
-- Replace the is_current=true partial index from V66 with an end_at IS NULL
-- partial index. Both semantics hold during migration (existing rows have
-- is_current=true AND end_at IS NULL). The new index is the authoritative constraint.

DROP INDEX IF EXISTS idx_assignment_current_unique;

CREATE UNIQUE INDEX IF NOT EXISTS idx_assignment_active_unique
    ON assignment(work_order_id)
    WHERE end_at IS NULL;

-- ── assignment_aud — mirror new columns ──────────────────────────────────────

ALTER TABLE assignment_aud
    ADD COLUMN IF NOT EXISTS end_at               TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS superseded_by        UUID,
    ADD COLUMN IF NOT EXISTS reassignment_reason  TEXT,
    ADD COLUMN IF NOT EXISTS reason_notes         TEXT,
    ADD COLUMN IF NOT EXISTS appointment_impact_reason TEXT;
