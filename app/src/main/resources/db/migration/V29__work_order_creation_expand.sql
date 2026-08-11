-- V29__work_order_creation_expand.sql
-- Expand-phase changes supporting WO-128 work order creation with deadline derivation.
-- All changes are additive (no column drops, no renames on existing populated columns).

-- ============================================================
-- 1. applied_sla_policy_id — snapshot of the policy row used
--    at creation time; prevents retroactive rewrites when the
--    sla_policy table is later updated.
-- ============================================================
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS applied_sla_policy_id UUID
        REFERENCES sla_policy (id) ON DELETE SET NULL;

ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS applied_sla_policy_id UUID;

-- ============================================================
-- 2. Reference-number sequence
--    References are formatted WO-{zero-padded seq} by the application.
--    Starting at 1000 to leave room for demo/fixture references.
-- ============================================================
CREATE SEQUENCE IF NOT EXISTS work_order_ref_seq
    START WITH 1000
    INCREMENT BY 1
    NO CYCLE;

-- ============================================================
-- 3. Seed an inactive SLA policy tier for testing the 422 path
--    (priority URGENT with active=false, so no active policy exists).
--    The seeded placeholder rows from V2 are all active; this adds
--    a row that should never be resolved.
-- ============================================================
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction, effective_from, active, version)
SELECT '00000000-0000-7001-8000-000000000099', 'HIGH', 30, 60, 0.80, '2020-01-01T00:00:00Z', FALSE, 0
WHERE NOT EXISTS (
    SELECT 1 FROM sla_policy WHERE id = '00000000-0000-7001-8000-000000000099'
);
