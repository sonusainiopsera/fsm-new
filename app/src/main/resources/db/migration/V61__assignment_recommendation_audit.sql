-- V61: Assignment recommendation audit columns
-- Extends the assignment table with attribution, recommendation snapshot reference,
-- override audit, and a unique active-assignment guard per work order.

-- ── Assignment table extensions ───────────────────────────────────────────────
ALTER TABLE assignment
    ADD COLUMN IF NOT EXISTS assigned_by                UUID,
    ADD COLUMN IF NOT EXISTS recommendation_snapshot_id UUID,
    ADD COLUMN IF NOT EXISTS recommendation_rank        INTEGER,
    ADD COLUMN IF NOT EXISTS recommendation_score       NUMERIC(7,6),
    ADD COLUMN IF NOT EXISTS override_reason            TEXT,
    ADD COLUMN IF NOT EXISTS snapshot_stale             BOOLEAN NOT NULL DEFAULT FALSE;

-- ── Assignment audit table extensions (Envers mirrors) ───────────────────────
ALTER TABLE assignment_aud
    ADD COLUMN IF NOT EXISTS assigned_by                UUID,
    ADD COLUMN IF NOT EXISTS recommendation_snapshot_id UUID,
    ADD COLUMN IF NOT EXISTS recommendation_rank        INTEGER,
    ADD COLUMN IF NOT EXISTS recommendation_score       NUMERIC(7,6),
    ADD COLUMN IF NOT EXISTS override_reason            TEXT,
    ADD COLUMN IF NOT EXISTS snapshot_stale             BOOLEAN;

-- ── Unique partial index: at most one active assignment per work order ────────
-- released_at IS NULL means the assignment has not been superseded.
CREATE UNIQUE INDEX IF NOT EXISTS uq_assignment_active_work_order
    ON assignment (work_order_id)
    WHERE released_at IS NULL;

-- ── Index for snapshot look-up (joining back from assignment to snapshot) ─────
CREATE INDEX IF NOT EXISTS idx_assignment_snapshot_id
    ON assignment (recommendation_snapshot_id)
    WHERE recommendation_snapshot_id IS NOT NULL;
