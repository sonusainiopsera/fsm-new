-- =============================================================================
-- V47: SLA breach table and supporting indexes (WO-144)
-- Additive-only: new tables and indexes; existing schema untouched.
--
-- sla_breach records each missed commitment exactly once per (work_order, breach_type).
-- The unique index on (work_order_id, breach_type) enforces this constraint at the
-- database level so concurrent sweep inserts are idempotent.
--
-- final_overrun_minutes is written at closure/cancellation and is intentionally
-- nullable until then — it distinguishes provisional overrun from the defensible
-- compliance figure used in root-cause reporting.
--
-- reason_code is nullable at detection and attributed later by a dispatcher/manager.
-- A database CHECK constraint mirrors the Java enum so no out-of-vocabulary value
-- can be persisted even through direct SQL.
--
-- Envers audits every field change so attribution corrections are fully traceable.
-- =============================================================================

CREATE TABLE IF NOT EXISTS sla_breach (
    id                      UUID         NOT NULL PRIMARY KEY,
    work_order_id           UUID         NOT NULL REFERENCES work_order(id),
    breach_type             VARCHAR(20)  NOT NULL,
    effective_deadline      TIMESTAMPTZ  NOT NULL,
    detected_at             TIMESTAMPTZ  NOT NULL,
    overrun_minutes         INTEGER      NOT NULL,
    paused_minutes_excluded INTEGER      NOT NULL DEFAULT 0,
    final_overrun_minutes   INTEGER,
    reason_code             VARCHAR(50),
    reason_note             VARCHAR(500),
    attributed_by           UUID,
    attributed_at           TIMESTAMPTZ,
    version                 INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT chk_sla_breach_type
        CHECK (breach_type IN ('RESPONSE', 'RESOLUTION')),
    CONSTRAINT chk_sla_breach_reason_code
        CHECK (reason_code IS NULL OR reason_code IN (
            'PARTS_UNAVAILABLE',
            'CUSTOMER_ACCESS_DENIED',
            'CAPACITY_SHORTFALL',
            'TRAVEL_DISRUPTION',
            'MISPRIORITISED_AT_INTAKE',
            'OTHER'
        )),
    CONSTRAINT chk_sla_breach_overrun_non_negative
        CHECK (overrun_minutes >= 0),
    CONSTRAINT chk_sla_breach_final_overrun_non_negative
        CHECK (final_overrun_minutes IS NULL OR final_overrun_minutes >= 0),
    CONSTRAINT chk_sla_breach_attributed_consistent
        CHECK ((attributed_by IS NULL) = (attributed_at IS NULL))
);

-- Unique: exactly one breach record per work order per breach type.
CREATE UNIQUE INDEX IF NOT EXISTS uq_sla_breach_work_order_type
    ON sla_breach (work_order_id, breach_type);

-- Supporting index for compliance aggregation queries.
CREATE INDEX IF NOT EXISTS idx_sla_breach_work_order_id
    ON sla_breach (work_order_id);

CREATE INDEX IF NOT EXISTS idx_sla_breach_detected_at
    ON sla_breach (detected_at);

-- Index for reason-code aggregation (root-cause analysis).
CREATE INDEX IF NOT EXISTS idx_sla_breach_reason_code
    ON sla_breach (reason_code) WHERE reason_code IS NOT NULL;

-- =============================================================================
-- Envers audit table
-- =============================================================================

CREATE TABLE IF NOT EXISTS sla_breach_aud (
    id                      UUID         NOT NULL,
    rev                     INTEGER      NOT NULL,
    revtype                 SMALLINT     NOT NULL,
    work_order_id           UUID,
    breach_type             VARCHAR(20),
    effective_deadline      TIMESTAMPTZ,
    detected_at             TIMESTAMPTZ,
    overrun_minutes         INTEGER,
    paused_minutes_excluded INTEGER,
    final_overrun_minutes   INTEGER,
    reason_code             VARCHAR(50),
    reason_note             VARCHAR(500),
    attributed_by           UUID,
    attributed_at           TIMESTAMPTZ,
    CONSTRAINT pk_sla_breach_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_sla_breach_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);
