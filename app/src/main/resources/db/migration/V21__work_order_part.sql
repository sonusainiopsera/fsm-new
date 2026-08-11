-- =============================================================================
-- V21: work_order_part — ledger-level consumption record for WO-149
-- =============================================================================
-- Expand-only: new table only; no destructive changes to existing tables.
-- Separate from work_order_part_consumption (V18 guard table for PartsReconciledGuard).
-- Envers-audited via work_order_part_aud.
-- =============================================================================

CREATE TABLE IF NOT EXISTS work_order_part (
    id               UUID         NOT NULL,
    work_order_id    UUID         NOT NULL,
    part_id          UUID         NOT NULL,
    stock_location_id UUID        NOT NULL,
    quantity         INTEGER      NOT NULL,
    reason_code      VARCHAR(100) NOT NULL,
    ledger_entry_id  UUID,
    actor_user_id    UUID         NOT NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_work_order_part PRIMARY KEY (id),
    CONSTRAINT fk_wop_work_order  FOREIGN KEY (work_order_id) REFERENCES work_order (id),
    CONSTRAINT fk_wop_part        FOREIGN KEY (part_id)       REFERENCES part (id),
    CONSTRAINT fk_wop_location    FOREIGN KEY (stock_location_id) REFERENCES stock_location (id),
    CONSTRAINT chk_wop_quantity_nonzero CHECK (quantity <> 0)
);

COMMENT ON TABLE work_order_part IS
    'Ledger-level record of parts consumed or returned against a work order. '
    'Positive quantity = consumption; negative quantity = return. '
    'Envers-audited via work_order_part_aud. '
    'Separate from work_order_part_consumption (V18 guard table).';

CREATE INDEX IF NOT EXISTS idx_wop_work_order_id
    ON work_order_part (work_order_id);

CREATE INDEX IF NOT EXISTS idx_wop_part_id
    ON work_order_part (part_id);

-- =============================================================================
-- Envers AUD table for work_order_part
-- =============================================================================

CREATE TABLE IF NOT EXISTS work_order_part_aud (
    id                UUID         NOT NULL,
    rev               INTEGER      NOT NULL,
    revtype           SMALLINT     NOT NULL,
    work_order_id     UUID,
    part_id           UUID,
    stock_location_id UUID,
    quantity          INTEGER,
    reason_code       VARCHAR(100),
    ledger_entry_id   UUID,
    actor_user_id     UUID,
    occurred_at       TIMESTAMPTZ,
    CONSTRAINT pk_work_order_part_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_work_order_part_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX IF NOT EXISTS idx_work_order_part_aud_rev_brin
    ON work_order_part_aud USING brin (rev);
