-- =============================================================================
-- V31: Work order creation fields (WO-128)
-- Expand-only: adds fault_description, reference, applied_sla_policy_id columns.
-- Creates reference number sequence and unique index.
-- Adds inactive SLA policy placeholder so AC-3 (policy-missing 422) can be tested.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Expand work_order with intake and policy-snapshot columns
-- ---------------------------------------------------------------------------
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS fault_description    TEXT,
    ADD COLUMN IF NOT EXISTS reference            VARCHAR(20),
    ADD COLUMN IF NOT EXISTS applied_sla_policy_id UUID REFERENCES sla_policy(id);

-- Reference number sequence: human-readable WO-XXXXXXXX format
CREATE SEQUENCE IF NOT EXISTS work_order_reference_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- Unique index on reference (non-null rows only; NULL during backfill window)
CREATE UNIQUE INDEX IF NOT EXISTS uq_work_order_reference
    ON work_order (reference) WHERE reference IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Mirror new columns into Envers audit table
-- ---------------------------------------------------------------------------
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS fault_description     TEXT,
    ADD COLUMN IF NOT EXISTS reference             VARCHAR(20),
    ADD COLUMN IF NOT EXISTS applied_sla_policy_id UUID;
