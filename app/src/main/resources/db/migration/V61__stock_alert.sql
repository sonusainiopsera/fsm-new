-- WO-152: stock_alert table for deduplication and hysteresis of stock threshold alerts.
-- Unique constraint on active row per (part, location, alert_type) prevents duplicate active alerts.
-- hold_reason_policy table holds runtime-configurable deadline-clock behaviour per hold reason,
-- seeded with placeholder values pending SLA tier ratification (BR-14).

CREATE TABLE stock_alert (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    part_id             UUID        NOT NULL,
    stock_location_id   UUID        NOT NULL,
    alert_type          VARCHAR(20) NOT NULL,
    state               VARCHAR(10) NOT NULL,
    raised_at           TIMESTAMPTZ NOT NULL,
    last_notified_at    TIMESTAMPTZ NOT NULL,
    cleared_at          TIMESTAMPTZ,
    CONSTRAINT pk_stock_alert               PRIMARY KEY (id),
    CONSTRAINT fk_stock_alert_part          FOREIGN KEY (part_id)          REFERENCES part (id),
    CONSTRAINT chk_stock_alert_type         CHECK (alert_type IN ('LOW_STOCK', 'STOCKOUT')),
    CONSTRAINT chk_stock_alert_state        CHECK (state      IN ('ACTIVE', 'CLEARED'))
);

-- Exactly one ACTIVE alert per (part, location, type) — prevents duplicate notifications.
CREATE UNIQUE INDEX uix_stock_alert_active
    ON stock_alert (part_id, stock_location_id, alert_type)
    WHERE state = 'ACTIVE';

CREATE INDEX ix_stock_alert_state            ON stock_alert (state);
CREATE INDEX ix_stock_alert_part_location    ON stock_alert (part_id, stock_location_id);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────
-- hold_reason_policy: runtime-configurable deadline-clock behaviour per hold reason.
-- Deadline-clock behaviour for AWAITING_PARTS is pending SLA tier ratification (BR-14).
-- ─────────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE hold_reason_policy (
    hold_reason_code            VARCHAR(50) NOT NULL,
    deadline_clock_behaviour    VARCHAR(10) NOT NULL DEFAULT 'TBD',
    notes                       TEXT,
    CONSTRAINT pk_hold_reason_policy        PRIMARY KEY (hold_reason_code),
    CONSTRAINT fk_hold_reason_policy_reason FOREIGN KEY (hold_reason_code) REFERENCES hold_reason (code),
    CONSTRAINT chk_deadline_clock_behaviour CHECK (deadline_clock_behaviour IN ('PAUSE', 'RUN', 'TBD'))
);

-- Seed AWAITING_PARTS with placeholder pending ratification.
-- Set deadline_clock_behaviour to PAUSE or RUN once SLA tiers are approved.
INSERT INTO hold_reason_policy (hold_reason_code, deadline_clock_behaviour, notes)
VALUES ('AWAITING_PARTS', 'TBD',
        'Deadline-clock behaviour for AWAITING_PARTS is pending SLA tier ratification. '
        'Update to PAUSE or RUN once tiers are approved by the SLA steering committee.');
