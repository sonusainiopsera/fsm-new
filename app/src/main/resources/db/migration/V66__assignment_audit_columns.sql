-- V66: Extend the assignment table with recommendation and override audit columns (WO-138).
--
-- Expand-only migration: all new columns are nullable (backwards compatible with existing rows).
-- =============================================================================

-- ── assignment ─────────────────────────────────────────────────────────────

ALTER TABLE assignment
    ADD COLUMN IF NOT EXISTS assigned_by                  UUID,
    ADD COLUMN IF NOT EXISTS recommendation_snapshot_id   UUID REFERENCES recommendation_snapshot(id),
    ADD COLUMN IF NOT EXISTS recommendation_rank          INTEGER,
    ADD COLUMN IF NOT EXISTS recommendation_score         NUMERIC(7, 6),
    ADD COLUMN IF NOT EXISTS override_reason              TEXT,
    ADD COLUMN IF NOT EXISTS snapshot_stale               BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN assignment.assigned_by IS
    'User ID of the dispatcher or admin who created this assignment.';
COMMENT ON COLUMN assignment.recommendation_snapshot_id IS
    'The recommendation snapshot consulted at assignment time; null when no snapshot was used.';
COMMENT ON COLUMN assignment.recommendation_rank IS
    'The chosen technician rank within the snapshot; null when the technician was absent from the snapshot.';
COMMENT ON COLUMN assignment.recommendation_score IS
    'Composite score from the snapshot for the chosen technician; null when absent.';
COMMENT ON COLUMN assignment.override_reason IS
    'Mandatory dispatcher override reason when rank is null or > 3.';
COMMENT ON COLUMN assignment.snapshot_stale IS
    'True when the snapshot was older than the configured staleness window at assignment time.';

-- Unique active-assignment invariant: at most one is_current=true row per work order.
-- The index was already created in V1 as a non-unique partial index; replace it with a
-- unique partial index to enforce the invariant at the database level.
DROP INDEX IF EXISTS idx_assignment_current;
CREATE UNIQUE INDEX IF NOT EXISTS idx_assignment_current_unique
    ON assignment(work_order_id)
    WHERE is_current = true;

-- ── assignment_aud ─────────────────────────────────────────────────────────

ALTER TABLE assignment_aud
    ADD COLUMN IF NOT EXISTS assigned_by                  UUID,
    ADD COLUMN IF NOT EXISTS recommendation_snapshot_id   UUID,
    ADD COLUMN IF NOT EXISTS recommendation_rank          INTEGER,
    ADD COLUMN IF NOT EXISTS recommendation_score         NUMERIC(7, 6),
    ADD COLUMN IF NOT EXISTS override_reason              TEXT,
    ADD COLUMN IF NOT EXISTS snapshot_stale               BOOLEAN;
