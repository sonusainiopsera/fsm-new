-- V62: Assignment supersede model and appointment window columns
--
-- Adds the supersede chain to the assignment table so reassignment history is
-- append-only and fully reconstructible.  Adds confirmed appointment window
-- columns to work_order to support appointment protection at reassignment time.
-- All changes are additive and backward compatible.

-- ── 1. Assignment: supersede, reason, and appointment-impact columns ──────────
ALTER TABLE assignment
    ADD COLUMN IF NOT EXISTS end_at                   TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS superseded_by            UUID        NULL
        REFERENCES assignment(id),
    ADD COLUMN IF NOT EXISTS reassignment_reason      TEXT        NULL
        CHECK (reassignment_reason IN (
            'TECHNICIAN_UNAVAILABLE', 'JOB_OVERRUN', 'SKILL_MISMATCH',
            'SLA_RISK', 'CUSTOMER_REQUEST', 'PARTS_UNAVAILABLE', 'OTHER'
        )),
    ADD COLUMN IF NOT EXISTS reason_notes             TEXT        NULL,
    ADD COLUMN IF NOT EXISTS appointment_impact_reason TEXT       NULL;

-- ── 2. Mirror new columns in assignment_AUD ───────────────────────────────────
ALTER TABLE assignment_aud
    ADD COLUMN IF NOT EXISTS end_at                    TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS superseded_by             UUID        NULL,
    ADD COLUMN IF NOT EXISTS reassignment_reason       TEXT        NULL,
    ADD COLUMN IF NOT EXISTS reason_notes              TEXT        NULL,
    ADD COLUMN IF NOT EXISTS appointment_impact_reason TEXT        NULL;

-- ── 3. Adjust unique active-assignment index to use end_at ────────────────────
-- The prior index (from V61) used released_at IS NULL.
-- Active assignment is now defined as end_at IS NULL.
DROP INDEX IF EXISTS uq_assignment_active_work_order;

CREATE UNIQUE INDEX IF NOT EXISTS uq_assignment_active_work_order
    ON assignment (work_order_id)
    WHERE end_at IS NULL;

-- ── 4. Appointment window columns on work_order ───────────────────────────────
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS appointment_window_start TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS appointment_window_end   TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS appointment_confirmed    BOOLEAN     NOT NULL DEFAULT FALSE;

-- ── 5. Mirror appointment columns in work_order_AUD ──────────────────────────
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS appointment_window_start TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS appointment_window_end   TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS appointment_confirmed    BOOLEAN     NULL;
