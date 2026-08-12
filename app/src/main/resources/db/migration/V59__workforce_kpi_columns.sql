-- V59__workforce_kpi_columns.sql
-- WO-163: Technician utilization and jobs-per-day KPI projections.
-- Expand-only: adds partial_bucket and incomplete_data flags to kpi_projection
-- and extends the maturity check vocabulary.
-- No existing columns renamed or removed.

-- ============================================================
-- 1. Extend kpi_projection with workforce-specific flags
-- ============================================================
-- partial_bucket: TRUE when the ISO week at the window boundary is incomplete
-- (started before or ends after the query window). Partial weeks are excluded
-- from trend comparisons by default unless the caller requests inclusive mode.
-- incomplete_data: TRUE when the hours-worked source is absent for a technician-week
-- and the utilization row cannot be computed with full confidence.

ALTER TABLE kpi_projection
    ADD COLUMN IF NOT EXISTS partial_bucket   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS incomplete_data  BOOLEAN NOT NULL DEFAULT FALSE;

-- ============================================================
-- 2. Extend maturity CHECK vocabulary
-- ============================================================
-- Adds INCOMPLETE_DATA to represent a projection row where the hours-worked source
-- was absent for one or more technician-weeks in the window; such rows are excluded
-- from the weighted ALL rollup so they cannot silently drag the team rate.
ALTER TABLE kpi_projection DROP CONSTRAINT IF EXISTS chk_kpi_maturity;
ALTER TABLE kpi_projection ADD CONSTRAINT chk_kpi_maturity
    CHECK (maturity IN (
        'PROVISIONAL', 'STABILISING', 'MATURE',
        'NOT_MEANINGFUL',
        'BASELINE_PENDING', 'WORSENED', 'IMPROVED', 'UNCHANGED',
        'INCOMPLETE_DATA'
    ));
