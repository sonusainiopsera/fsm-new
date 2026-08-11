-- V20__work_order_part.sql
-- Adds the production-grade work_order_part consumption record linked to the stock ledger.
-- Separate from the guard-support work_order_parts_consumption table (V17).
-- Expand-only: no columns removed or renamed.

CREATE TABLE work_order_part (
    id                UUID        NOT NULL,
    work_order_id     UUID        NOT NULL,
    part_id           UUID        NOT NULL,
    stock_location_id UUID        NOT NULL,
    quantity          INTEGER     NOT NULL,
    movement_type     VARCHAR(20) NOT NULL,   -- CONSUME | RETURN
    reason_code       VARCHAR(100),
    ledger_entry_id   UUID,                   -- FK to stock_ledger row (informational)
    actor_user_id     UUID        NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_work_order_part       PRIMARY KEY (id),
    CONSTRAINT fk_wop_work_order        FOREIGN KEY (work_order_id)     REFERENCES work_order (id),
    CONSTRAINT fk_wop_part              FOREIGN KEY (part_id)           REFERENCES part (id),
    CONSTRAINT fk_wop_stock_location    FOREIGN KEY (stock_location_id) REFERENCES stock_location (id),
    CONSTRAINT chk_wop_quantity_pos     CHECK (quantity > 0),
    CONSTRAINT chk_wop_movement_type    CHECK (movement_type IN ('CONSUME', 'RETURN'))
);

CREATE INDEX idx_work_order_part_wo ON work_order_part (work_order_id);
CREATE INDEX idx_work_order_part_part ON work_order_part (part_id, stock_location_id);

-- Envers audit table
CREATE TABLE work_order_part_aud (
    id                UUID        NOT NULL,
    REV               INTEGER     NOT NULL,
    REVTYPE           SMALLINT,
    work_order_id     UUID,
    part_id           UUID,
    stock_location_id UUID,
    quantity          INTEGER,
    movement_type     VARCHAR(20),
    reason_code       VARCHAR(100),
    ledger_entry_id   UUID,
    actor_user_id     UUID,
    occurred_at       TIMESTAMPTZ,
    CONSTRAINT pk_work_order_part_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_work_order_part_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX brin_work_order_part_aud_rev ON work_order_part_aud USING BRIN (REV);
