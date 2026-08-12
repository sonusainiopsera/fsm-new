-- WO-144: SLA breach table with controlled-vocabulary reason codes and Envers audit mirror

CREATE TABLE sla_breach (
    id                      UUID          NOT NULL,
    work_order_id           UUID          NOT NULL,
    -- RESPONSE = response-deadline missed; RESOLUTION = resolution-deadline missed
    breach_type             VARCHAR(20)   NOT NULL
        CONSTRAINT chk_sla_breach_type CHECK (breach_type IN ('RESPONSE', 'RESOLUTION')),
    effective_deadline      TIMESTAMPTZ   NOT NULL,
    detected_at             TIMESTAMPTZ   NOT NULL,
    -- overrun_minutes >= 0 enforced by application; clamped at zero for negative raw values
    overrun_minutes         INTEGER       NOT NULL,
    paused_minutes_excluded INTEGER       NOT NULL DEFAULT 0,
    -- NULL until closure; written once and never overwritten (idempotent finalisation)
    final_overrun_minutes   INTEGER,
    -- reason_code must be from the controlled vocabulary; NULL = awaiting attribution
    reason_code             VARCHAR(60)
        CONSTRAINT chk_sla_breach_reason_code CHECK (reason_code IN (
            'PARTS_UNAVAILABLE',
            'CUSTOMER_ACCESS_DENIED',
            'CAPACITY_SHORTFALL',
            'TRAVEL_DISRUPTION',
            'MISPRIORITISED_AT_INTAKE'
        )),
    reason_note             VARCHAR(500),
    attributed_by           UUID,
    attributed_at           TIMESTAMPTZ,
    version                 INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT pk_sla_breach PRIMARY KEY (id)
);

-- Exactly one breach record per work order per breach type (idempotent detection)
CREATE UNIQUE INDEX uq_sla_breach_per_type
    ON sla_breach (work_order_id, breach_type);

-- Compliance report queries filter/aggregate by detection date
CREATE INDEX idx_sla_breach_detected_at
    ON sla_breach (detected_at);

-- Root-cause aggregation by reason code; partial because most rows are unattributed at insert time
CREATE INDEX idx_sla_breach_reason_code
    ON sla_breach (reason_code)
    WHERE reason_code IS NOT NULL;

-- ─── Envers audit mirror ─────────────────────────────────────────────────────
-- Created here so ddl-auto=validate passes at startup (Envers expects the table to exist).
CREATE TABLE sla_breach_AUD (
    id                      UUID          NOT NULL,
    REV                     INTEGER       NOT NULL,
    REVTYPE                 SMALLINT,
    work_order_id           UUID,
    breach_type             VARCHAR(20),
    effective_deadline      TIMESTAMPTZ,
    detected_at             TIMESTAMPTZ,
    overrun_minutes         INTEGER,
    paused_minutes_excluded INTEGER,
    final_overrun_minutes   INTEGER,
    reason_code             VARCHAR(60),
    reason_note             VARCHAR(500),
    attributed_by           UUID,
    attributed_at           TIMESTAMPTZ,
    version                 INTEGER,
    CONSTRAINT pk_sla_breach_aud PRIMARY KEY (id, REV),
    CONSTRAINT fk_sla_breach_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);
