-- =============================================================================
-- V56: work_order_required_part — parts BOM for dispatch availability scoring
-- =============================================================================
-- Records which parts (and quantities) a work order requires before assignment.
-- Consumed by the dispatch parts-availability scoring factor (WO-151).
-- Separate from work_order_part (V21) which records parts consumed after work.
-- =============================================================================

CREATE TABLE IF NOT EXISTS work_order_required_part (
    id               UUID         NOT NULL,
    work_order_id    UUID         NOT NULL REFERENCES work_order (id) ON DELETE CASCADE,
    part_id          UUID         NOT NULL REFERENCES part (id),
    required_quantity INTEGER     NOT NULL CHECK (required_quantity > 0),
    added_by_user_id  UUID,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_work_order_required_part PRIMARY KEY (id),
    CONSTRAINT uq_wo_required_part         UNIQUE (work_order_id, part_id)
);

COMMENT ON TABLE work_order_required_part IS
    'Bill-of-materials for a work order: parts the dispatcher expects to be needed. '
    'Consumed by the dispatch scoring engine to advise on candidate availability. '
    'Separate from work_order_part (consumed/returned records).';

CREATE INDEX IF NOT EXISTS idx_worp_work_order_id
    ON work_order_required_part (work_order_id);

CREATE INDEX IF NOT EXISTS idx_worp_part_id
    ON work_order_required_part (part_id);

-- =============================================================================
-- Assignment audit columns for parts advisory warnings (WO-151)
-- =============================================================================
-- Extend the assignment table to persist parts-availability warnings and
-- any dispatcher acknowledgement so parts-driven SLA breaches can be
-- root-caused under BR-14.
-- =============================================================================

ALTER TABLE assignment
    ADD COLUMN IF NOT EXISTS parts_warning_code              VARCHAR(100),
    ADD COLUMN IF NOT EXISTS parts_shortfall_summary         TEXT,
    ADD COLUMN IF NOT EXISTS parts_warning_acknowledged      BOOLEAN,
    ADD COLUMN IF NOT EXISTS parts_warning_acknowledgement_reason TEXT;

COMMENT ON COLUMN assignment.parts_warning_code IS
    'PARTS_UNAVAILABLE or PARTS_PARTIALLY_STOCKED when the dispatcher was shown an advisory warning.';
COMMENT ON COLUMN assignment.parts_shortfall_summary IS
    'JSON summary of per-part shortfalls at assignment time, for breach root-cause (BR-14).';
COMMENT ON COLUMN assignment.parts_warning_acknowledged IS
    'True when the dispatcher explicitly acknowledged the parts advisory warning.';
COMMENT ON COLUMN assignment.parts_warning_acknowledgement_reason IS
    'Free-text reason the dispatcher provided when acknowledging the parts warning.';

-- Extend the Envers audit table to capture the new columns
ALTER TABLE assignment_aud
    ADD COLUMN IF NOT EXISTS parts_warning_code              VARCHAR(100),
    ADD COLUMN IF NOT EXISTS parts_shortfall_summary         TEXT,
    ADD COLUMN IF NOT EXISTS parts_warning_acknowledged      BOOLEAN,
    ADD COLUMN IF NOT EXISTS parts_warning_acknowledgement_reason TEXT;
