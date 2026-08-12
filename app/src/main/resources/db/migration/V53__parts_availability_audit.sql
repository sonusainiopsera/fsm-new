-- V53: Parts availability — required-part declarations and assignment audit columns
-- Adds work_order_required_part for work order part requirements,
-- and extends the assignment table with parts-availability warning audit fields.

-- ── Work order required parts ──────────────────────────────────────────────
CREATE TABLE work_order_required_part (
    id                UUID        NOT NULL,
    work_order_id     UUID        NOT NULL,
    part_id           UUID        NOT NULL,
    quantity_required INTEGER     NOT NULL,
    CONSTRAINT pk_wo_required_part      PRIMARY KEY (id),
    CONSTRAINT fk_wo_required_part_wo   FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT fk_wo_required_part_part FOREIGN KEY (part_id) REFERENCES part (id),
    CONSTRAINT uq_wo_required_part      UNIQUE (work_order_id, part_id),
    CONSTRAINT chk_wo_required_part_qty CHECK (quantity_required > 0)
);

CREATE INDEX idx_wo_required_part_wo ON work_order_required_part (work_order_id);

-- ── Assignment parts-warning audit columns ─────────────────────────────────
ALTER TABLE assignment
    ADD COLUMN IF NOT EXISTS parts_warning_code              VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parts_shortfall_json            TEXT,
    ADD COLUMN IF NOT EXISTS acknowledge_warnings            BOOLEAN,
    ADD COLUMN IF NOT EXISTS warning_acknowledgement_reason  VARCHAR(500);
