-- =============================================================================
-- V23: Expand sla_policy with version, active flag, corrected constraints (WO-142)
-- Expand-only: adds columns and replaces incorrect CHECK constraints.
-- at_risk_fraction constraint changed from (>0 AND <1) to (>=0.50 AND <=1.00).
-- resolution >= response constraint added.
-- =============================================================================

-- Add optimistic-lock version column (not audited per do_not_audit_optimistic_locking_field=true)
ALTER TABLE sla_policy
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

-- Add explicit active flag (separate from effective_to derivation; nullable-safe)
ALTER TABLE sla_policy
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true;

-- Fix the at_risk_fraction check constraint (was >0 AND <1, must be >=0.50 AND <=1.00)
ALTER TABLE sla_policy
    DROP CONSTRAINT IF EXISTS chk_sla_policy_at_risk_fraction;

ALTER TABLE sla_policy
    ADD CONSTRAINT chk_sla_policy_at_risk_fraction
        CHECK (at_risk_fraction >= 0.50 AND at_risk_fraction <= 1.00);

-- Add missing resolution >= response constraint
ALTER TABLE sla_policy
    DROP CONSTRAINT IF EXISTS chk_sla_policy_resolution_gte_response;

ALTER TABLE sla_policy
    ADD CONSTRAINT chk_sla_policy_resolution_gte_response
        CHECK (resolution_minutes >= response_minutes);

-- Index for policy resolution queries: (priority, effective_from DESC)
CREATE INDEX IF NOT EXISTS idx_sla_policy_priority_effective_from
    ON sla_policy (priority, effective_from DESC);

-- Mirror active into the Envers audit table (version excluded per Envers config)
ALTER TABLE sla_policy_aud
    ADD COLUMN IF NOT EXISTS active BOOLEAN;
