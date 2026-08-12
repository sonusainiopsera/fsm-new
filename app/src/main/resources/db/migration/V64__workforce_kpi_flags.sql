-- V64: Workforce KPI projection flags and maturity constraint expansion (WO-163).
-- Expand-phase only — no destructive changes.

-- 1. Fix maturity CHECK constraint to include BASELINE_PENDING (WO-162 oversight in V27).
ALTER TABLE kpi_projection DROP CONSTRAINT IF EXISTS chk_kpi_maturity;
ALTER TABLE kpi_projection ADD CONSTRAINT chk_kpi_maturity
    CHECK (maturity IN ('CURRENT', 'HISTORICAL', 'SEEDED', 'BASELINE_PENDING'));

-- 2. Partial-week flag: TRUE when this projection row covers an ISO week that was
--    still in progress at the window boundary. Partial weeks are excluded from trend
--    comparison by default (AC-3).
ALTER TABLE kpi_projection
    ADD COLUMN IF NOT EXISTS is_partial_week BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. Incomplete-data flag: TRUE when the authoritative hours-worked source (roster/shift
--    data) was absent for one or more technician-weeks contributing to this projection.
--    The row is still persisted with whatever field-hour data is available, but callers
--    must surface the incomplete flag rather than silently treating the value as final.
ALTER TABLE kpi_projection
    ADD COLUMN IF NOT EXISTS is_incomplete_data BOOLEAN NOT NULL DEFAULT FALSE;

DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'fieldservice') THEN
        -- No new tables; existing grants on kpi_projection already cover new columns.
        NULL;
    END IF;
END $$;
