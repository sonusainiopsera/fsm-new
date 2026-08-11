-- =============================================================================
-- V19: Hold reason vocabulary and hold interval tracking (WO-126)
-- Expand-only: adds new tables and columns; no existing constraint is modified.
-- =============================================================================

-- =============================================================================
-- hold_reason: controlled vocabulary for work-order holds (BR-09).
-- Code is the natural primary key so FK references remain human-readable.
-- active=false inactivates a code for new holds but preserves historical rows.
-- =============================================================================
CREATE TABLE hold_reason (
    code        VARCHAR(100)  NOT NULL PRIMARY KEY,
    label       VARCHAR(255)  NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT true,
    sort_order  INTEGER       NOT NULL DEFAULT 0
);

INSERT INTO hold_reason (code, label, active, sort_order) VALUES
    ('AWAITING_PARTS',      'Awaiting parts or materials',             true,  10),
    ('CUSTOMER_UNAVAILABLE','Customer unavailable or not on-site',     true,  20),
    ('ACCESS_DENIED',       'Site access denied',                       true,  30),
    ('WEATHER',             'Weather conditions preventing work',       true,  40),
    ('SAFETY_CONCERN',      'Safety concern identified',                true,  50),
    ('AWAITING_APPROVAL',   'Awaiting management or customer approval', true,  60),
    ('OTHER',               'Other – see supplementary note',          false, 99);

-- =============================================================================
-- work_order.cumulative_hold_minutes: running total of all completed hold
-- intervals for this work order. Updated at RESUME (and dangling-hold closure).
-- =============================================================================
ALTER TABLE work_order ADD COLUMN cumulative_hold_minutes INTEGER NOT NULL DEFAULT 0;

-- Mirror the new column into the Envers audit table.
ALTER TABLE work_order_aud ADD COLUMN cumulative_hold_minutes INTEGER;

-- =============================================================================
-- work_order_hold: one row per hold interval, audited.
-- Partial unique index guarantees at most one open hold per work order.
-- ended_at IS NULL means the hold is still open.
-- =============================================================================
CREATE TABLE work_order_hold (
    id              UUID          NOT NULL PRIMARY KEY,
    work_order_id   UUID          NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    reason_code     VARCHAR(100)  NOT NULL REFERENCES hold_reason(code),
    note            VARCHAR(500),
    started_at      TIMESTAMPTZ   NOT NULL,
    ended_at        TIMESTAMPTZ,
    started_by      UUID,
    ended_by        UUID,

    CONSTRAINT chk_hold_ended_after_started
        CHECK (ended_at IS NULL OR ended_at >= started_at)
);

-- Partial unique index: only one open hold per work order.
CREATE UNIQUE INDEX uq_work_order_hold_open
    ON work_order_hold(work_order_id)
    WHERE ended_at IS NULL;

CREATE INDEX idx_work_order_hold_work_order_id
    ON work_order_hold(work_order_id);

-- =============================================================================
-- work_order_hold_aud: Hibernate Envers audit table.
-- Mirrors work_order_hold; Envers validates this table exists at startup.
-- =============================================================================
CREATE TABLE work_order_hold_aud (
    id              UUID        NOT NULL,
    rev             INTEGER     NOT NULL,
    revtype         SMALLINT    NOT NULL,
    work_order_id   UUID,
    reason_code     VARCHAR(100),
    note            VARCHAR(500),
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    started_by      UUID,
    ended_by        UUID,
    CONSTRAINT pk_work_order_hold_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_work_order_hold_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE INDEX IF NOT EXISTS idx_work_order_hold_aud_rev_brin
    ON work_order_hold_aud USING brin (rev);
