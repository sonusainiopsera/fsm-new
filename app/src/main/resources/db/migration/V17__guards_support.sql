-- V17__guards_support.sql
-- Adds supporting tables for lifecycle precondition guards:
--   work_order_required_competency — certifications a work order demands before ASSIGN
--   work_order_labour_entry        — logged technician labour; COMPLETE guard checks this
--   work_order_parts_consumption   — parts consumed on a job; CLOSE guard checks reconciliation

CREATE TABLE work_order_required_competency (
    id                 UUID        NOT NULL,
    work_order_id      UUID        NOT NULL,
    certification_code VARCHAR(50) NOT NULL,
    CONSTRAINT pk_wo_req_comp      PRIMARY KEY (id),
    CONSTRAINT fk_wo_req_comp_wo   FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT uq_wo_req_comp      UNIQUE (work_order_id, certification_code)
);

CREATE INDEX idx_wo_req_comp_wo ON work_order_required_competency (work_order_id);

CREATE TABLE work_order_labour_entry (
    id            UUID         NOT NULL,
    work_order_id UUID         NOT NULL,
    technician_id UUID,
    minutes       INTEGER      NOT NULL,
    notes         VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_wo_labour_entry     PRIMARY KEY (id),
    CONSTRAINT fk_wo_labour_wo        FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT fk_wo_labour_tech      FOREIGN KEY (technician_id) REFERENCES technician (id),
    CONSTRAINT chk_wo_labour_minutes  CHECK (minutes > 0)
);

CREATE INDEX idx_wo_labour_wo ON work_order_labour_entry (work_order_id);

CREATE TABLE work_order_parts_consumption (
    id            UUID        NOT NULL,
    work_order_id UUID        NOT NULL,
    part_id       UUID        NOT NULL,
    quantity      INTEGER     NOT NULL,
    reconciled    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_wo_parts_consumption  PRIMARY KEY (id),
    CONSTRAINT fk_wo_parts_wo           FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT fk_wo_parts_part         FOREIGN KEY (part_id) REFERENCES part (id),
    CONSTRAINT chk_wo_parts_qty         CHECK (quantity > 0)
);

CREATE INDEX idx_wo_parts_consumption_wo ON work_order_parts_consumption (work_order_id);
