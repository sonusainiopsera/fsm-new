-- V18__hold_reason.sql
-- Introduces controlled hold reason vocabulary and hold interval persistence.
-- Expand-phase migration — all additions are backward compatible.

-- ============================================================
-- hold_reason — configurable vocabulary for work order holds
-- code        : machine-readable key (PK)
-- label       : human-readable display text
-- active      : false means retired; historical records still reference this code
-- sort_order  : UI display order
-- ============================================================
CREATE TABLE hold_reason (
    code        VARCHAR(50)  NOT NULL,
    label       VARCHAR(200) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_hold_reason PRIMARY KEY (code)
);

-- ============================================================
-- work_order_hold — interval table for hold periods
-- started_by / ended_by : app_user UUID of the actor
-- ended_at is nullable  : NULL means the hold is currently open
-- ============================================================
CREATE TABLE work_order_hold (
    id            UUID        NOT NULL,
    work_order_id UUID        NOT NULL,
    reason_code   VARCHAR(50) NOT NULL,
    note          VARCHAR(500),
    started_at    TIMESTAMPTZ NOT NULL,
    ended_at      TIMESTAMPTZ,
    started_by    UUID        NOT NULL,
    ended_by      UUID,
    CONSTRAINT pk_work_order_hold      PRIMARY KEY (id),
    CONSTRAINT fk_work_order_hold_wo   FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT fk_work_order_hold_code FOREIGN KEY (reason_code) REFERENCES hold_reason (code)
);

CREATE INDEX idx_work_order_hold_wo ON work_order_hold (work_order_id);

-- Enforces at most one open hold per work order at any given time.
-- ended_at IS NULL identifies the open (active) hold record.
CREATE UNIQUE INDEX uq_wo_hold_open ON work_order_hold (work_order_id) WHERE ended_at IS NULL;

-- ============================================================
-- work_order — add cumulative hold duration column
-- Sub-minute holds truncate toward zero (Duration.toMinutes() behaviour).
-- ============================================================
ALTER TABLE work_order ADD COLUMN cumulative_hold_minutes INTEGER NOT NULL DEFAULT 0;

-- Mirror the new column in the Envers audit shadow table
ALTER TABLE work_order_aud ADD COLUMN cumulative_hold_minutes INTEGER;

-- ============================================================
-- work_order_hold_aud — Envers audit shadow for hold intervals
-- ============================================================
CREATE TABLE work_order_hold_aud (
    id            UUID        NOT NULL,
    REV           INTEGER     NOT NULL,
    REVTYPE       SMALLINT,
    work_order_id UUID,
    reason_code   VARCHAR(50),
    note          VARCHAR(500),
    started_at    TIMESTAMPTZ,
    ended_at      TIMESTAMPTZ,
    started_by    UUID,
    ended_by      UUID,
    CONSTRAINT pk_work_order_hold_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_work_order_hold_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX brin_work_order_hold_aud_rev ON work_order_hold_aud USING BRIN (REV);

-- ============================================================
-- Seed initial hold reason vocabulary (BR-09)
-- One inactive code included to support the inactive-rejection test path.
-- ============================================================
INSERT INTO hold_reason (code, label, active, sort_order) VALUES
    ('AWAITING_PARTS',       'Awaiting Parts',         TRUE,  1),
    ('CUSTOMER_UNAVAILABLE', 'Customer Unavailable',   TRUE,  2),
    ('ACCESS_DENIED',        'Access Denied',          TRUE,  3),
    ('WEATHER',              'Weather Conditions',     TRUE,  4),
    ('SAFETY_CONCERN',       'Safety Concern',         TRUE,  5),
    ('AWAITING_APPROVAL',    'Awaiting Approval',      TRUE,  6),
    ('LEGACY_OTHER',         'Other (Legacy)',         FALSE, 99);
