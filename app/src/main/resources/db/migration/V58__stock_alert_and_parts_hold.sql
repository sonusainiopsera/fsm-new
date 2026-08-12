-- V58: Awaiting-parts hold and replenishment need signalling (WO-152)
-- Adds stock_alert for threshold-based alerting, hold_reason_policy for
-- deadline-clock behaviour per hold reason, and parts_unavailability_reason
-- column on work_order for BR-14 breach coding.
-- Expand-only: no columns removed or renamed.

-- ============================================================
-- stock_alert — persisted alert state for deduplication and hysteresis
-- ============================================================
-- Unique active-row constraint: at most one OPEN alert per (part, location, type).
-- state: OPEN = alert is active; CLEARED = resolved (historical row).
-- raised_at:        when the condition was first detected
-- last_notified_at: when the last notification was sent (debounce reference)
-- cleared_at:       when stock recovered above threshold
-- ============================================================
CREATE TABLE stock_alert (
    id                UUID        NOT NULL,
    part_id           UUID        NOT NULL,
    stock_location_id UUID        NOT NULL,
    alert_type        VARCHAR(30) NOT NULL,   -- LOW_STOCK | STOCKOUT | REPLENISHMENT_NEEDED
    state             VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    raised_at         TIMESTAMPTZ NOT NULL,
    last_notified_at  TIMESTAMPTZ,
    cleared_at        TIMESTAMPTZ,
    work_order_id     UUID,                   -- set when raised from a hold event
    CONSTRAINT pk_stock_alert           PRIMARY KEY (id),
    CONSTRAINT fk_stock_alert_part      FOREIGN KEY (part_id)           REFERENCES part (id),
    CONSTRAINT fk_stock_alert_location  FOREIGN KEY (stock_location_id) REFERENCES stock_location (id),
    CONSTRAINT chk_stock_alert_type     CHECK (alert_type IN ('LOW_STOCK', 'STOCKOUT', 'REPLENISHMENT_NEEDED')),
    CONSTRAINT chk_stock_alert_state    CHECK (state IN ('OPEN', 'CLEARED'))
);

-- One active (OPEN) row per (part, location, type) — deduplication without a locking join.
CREATE UNIQUE INDEX uq_stock_alert_open
    ON stock_alert (part_id, stock_location_id, alert_type)
    WHERE state = 'OPEN';

CREATE INDEX idx_stock_alert_state ON stock_alert (state);
CREATE INDEX idx_stock_alert_part_loc ON stock_alert (part_id, stock_location_id);

-- ============================================================
-- hold_reason_policy — runtime-configurable deadline-clock behaviour
-- Pending SLA tier ratification; seeded with placeholder values.
-- ============================================================
CREATE TABLE hold_reason_policy (
    hold_reason_code        VARCHAR(50)  NOT NULL,
    pauses_response_clock   BOOLEAN      NOT NULL DEFAULT FALSE,
    pauses_resolution_clock BOOLEAN      NOT NULL DEFAULT FALSE,
    max_hold_hours          INTEGER,             -- NULL = unlimited; used for escalation triggers
    note                    VARCHAR(500),        -- ratification status note
    CONSTRAINT pk_hold_reason_policy      PRIMARY KEY (hold_reason_code),
    CONSTRAINT fk_hold_reason_policy_code FOREIGN KEY (hold_reason_code) REFERENCES hold_reason (code)
);

-- Seed placeholder values; values are pending SLA tier ratification
INSERT INTO hold_reason_policy (hold_reason_code, pauses_response_clock, pauses_resolution_clock, max_hold_hours, note)
VALUES
    ('AWAITING_PARTS',       FALSE, FALSE, 72,
     'Placeholder — SLA tier not yet ratified. Does not pause clocks by default; review with SLA team.'),
    ('CUSTOMER_UNAVAILABLE', TRUE,  TRUE,  48,
     'Placeholder — pauses both clocks pending ratification.'),
    ('ACCESS_DENIED',        TRUE,  FALSE, 24,
     'Placeholder — pauses response clock only.'),
    ('WEATHER',              TRUE,  TRUE,  NULL,
     'Placeholder — unlimited hold; pauses both clocks.'),
    ('SAFETY_CONCERN',       TRUE,  TRUE,  NULL,
     'Placeholder — unlimited hold; pauses both clocks.'),
    ('AWAITING_APPROVAL',    FALSE, FALSE, 48,
     'Placeholder — does not pause clocks; escalation after 48 h.')
ON CONFLICT DO NOTHING;

-- ============================================================
-- work_order — parts_unavailability_reason for BR-14 breach coding
-- ============================================================
-- Records the coded reason when a work order is placed on AWAITING_PARTS hold.
-- Feeds the parts-caused repeat-visit metric and SLA breach coding under BR-14.
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS parts_unavailability_reason VARCHAR(50);

-- Mirror in Envers audit shadow table
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS parts_unavailability_reason VARCHAR(50);
