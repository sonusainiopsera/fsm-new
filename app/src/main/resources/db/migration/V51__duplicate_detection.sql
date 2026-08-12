-- =============================================================================
-- V51: Duplicate work order detection and linking
-- =============================================================================
-- Adds:
--   1. fault_signature text[] column on work_order with GIN index for
--      token-overlap queries (no external NLP — deterministic, explainable).
--   2. excluded_from_sla_compliance boolean on work_order (default false).
--      Set to true for duplicate-linked cancellations; filters SLA denominators.
--   3. work_order_duplicate_link table: append-only, unique source constraint,
--      FK to both work orders.
--   4. work_order_duplicate_link_aud for Hibernate Envers audit trail.
--   5. Mirror columns in work_order_aud so Envers revisions stay coherent.
-- =============================================================================

-- ── work_order: fault_signature column ─────────────────────────────────────
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS fault_signature text[] NOT NULL DEFAULT '{}';

-- GIN index enables index-assisted && (overlap) queries; never scans full table
CREATE INDEX IF NOT EXISTS idx_work_order_fault_signature_gin
    ON work_order USING GIN (fault_signature);

-- ── work_order: compliance exclusion flag ───────────────────────────────────
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS excluded_from_sla_compliance boolean NOT NULL DEFAULT false;

-- Partial index: most work orders are NOT excluded; index only the excluded minority
CREATE INDEX IF NOT EXISTS idx_work_order_excluded_compliance
    ON work_order(id) WHERE excluded_from_sla_compliance = true;

-- ── work_order_duplicate_link ───────────────────────────────────────────────
-- source_work_order_id is UNIQUE: one work order can be a duplicate of at most one other.
-- linked_by may be null when triggered by a system process.
-- Envers requires created_at / updated_at / version from BaseEntity.
CREATE TABLE IF NOT EXISTS work_order_duplicate_link (
    id                    UUID          NOT NULL PRIMARY KEY,
    source_work_order_id  UUID          NOT NULL,
    target_work_order_id  UUID          NOT NULL,
    reason                TEXT          NOT NULL,
    linked_by             UUID,
    linked_at             TIMESTAMPTZ   NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT uq_dup_link_source UNIQUE (source_work_order_id),
    CONSTRAINT fk_dup_link_source FOREIGN KEY (source_work_order_id)
        REFERENCES work_order(id),
    CONSTRAINT fk_dup_link_target FOREIGN KEY (target_work_order_id)
        REFERENCES work_order(id),
    CONSTRAINT chk_dup_no_self_link
        CHECK (source_work_order_id != target_work_order_id)
);

-- Index on target so "list all duplicates of WO-X" queries are fast
CREATE INDEX IF NOT EXISTS idx_dup_link_target
    ON work_order_duplicate_link(target_work_order_id);

-- ── work_order_duplicate_link_aud (Hibernate Envers) ───────────────────────
CREATE TABLE IF NOT EXISTS work_order_duplicate_link_aud (
    id                    UUID          NOT NULL,
    rev                   INTEGER       NOT NULL,
    revtype               SMALLINT      NOT NULL,
    source_work_order_id  UUID,
    target_work_order_id  UUID,
    reason                TEXT,
    linked_by             UUID,
    linked_at             TIMESTAMPTZ,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,

    CONSTRAINT pk_dup_link_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_dup_link_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- ── work_order_aud: mirror new columns for Envers coherence ────────────────
ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS fault_signature text[];

ALTER TABLE work_order_aud
    ADD COLUMN IF NOT EXISTS excluded_from_sla_compliance boolean;
