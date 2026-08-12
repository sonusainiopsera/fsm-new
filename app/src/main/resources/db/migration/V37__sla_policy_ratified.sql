-- V37: SLA policy ratified flag and update audit columns
-- Adds ratified flag (false = placeholder not yet approved by stakeholders),
-- updated_at and updated_by for admin mutation audit trail.
-- Expand-only: all changes are additive; no existing columns altered or dropped.
-- Relates to WO-198 (Q2 open question mitigation: ratified=false labels placeholder values).

ALTER TABLE sla_policy
    ADD COLUMN IF NOT EXISTS ratified   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);

-- Existing seed rows are unratified placeholder values pending stakeholder ratification.
-- DO NOT change ratified=true here; that requires explicit stakeholder sign-off.
UPDATE sla_policy SET ratified = FALSE WHERE ratified IS DISTINCT FROM FALSE;

-- Add same columns to Envers audit table so Hibernate Envers does not fail at runtime
ALTER TABLE sla_policy_aud
    ADD COLUMN IF NOT EXISTS ratified   BOOLEAN,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(255);
