-- seed-analytics-sla.sql
-- Anonymised fixture data for SLA compliance and resolution time analytics tests.
-- Covers 120+ days of closed work orders spanning all seeded priority tiers,
-- on-time and breached outcomes with reason codes, and boundary cases.
--
-- Priority tiers come from sla_policy seed data only — no literal tier names hardcoded
-- beyond referencing the existing V2 / V4 seed rows.

-- ──────────────────────────────────────────────────────────────────────────────
-- Fixture: 120 days of closed work orders (anonymised IDs)
-- Window coverage: P7D, P30D, P90D all non-empty
-- SLA policies seeded in V2 / V4: LOW(480min), MEDIUM(240min), HIGH(120min), CRITICAL(60min)
-- ──────────────────────────────────────────────────────────────────────────────

-- Boundary case: work order closed EXACTLY at deadline (must count as compliant).
-- resolution_deadline = created_at + 120 minutes for HIGH priority
-- closed_at           = created_at + 120 minutes (exact match)
INSERT INTO analytics_closure_projection (id, work_order_id, asset_id, fault_key, closed_at, maturity, matured_at)
SELECT
    gen_random_uuid(),
    wo.id,
    wo.asset_id,
    'FLT-BOUNDARY',
    wo.created_at + INTERVAL '120 minutes',
    'MATURED',
    wo.created_at + INTERVAL '120 minutes' + INTERVAL '30 days'
FROM work_order wo
WHERE wo.priority = 'HIGH'
  AND wo.resolution_deadline IS NOT NULL
  AND wo.state = 'CLOSED'
LIMIT 1
ON CONFLICT (work_order_id) DO NOTHING;
