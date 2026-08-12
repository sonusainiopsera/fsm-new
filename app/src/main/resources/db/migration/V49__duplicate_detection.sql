-- V49: Duplicate work order detection — fault signature, compliance exclusion, link table

-- Fault signature normalized token string on work_order.
-- GIN index via tsvector for index-assisted overlap pre-filtering.
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS fault_signature_tokens TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_wo_fault_sig_gin
    ON work_order USING GIN(to_tsvector('simple', COALESCE(fault_signature_tokens, '')));

-- Compliance exclusion flag: true when cancelled with DUPLICATE_REQUEST reason
ALTER TABLE work_order
    ADD COLUMN IF NOT EXISTS excluded_from_sla_compliance BOOLEAN NOT NULL DEFAULT FALSE;

-- Duplicate link table: one-to-one, source can only be a duplicate of one target
CREATE TABLE IF NOT EXISTS work_order_duplicate_link (
    id                   UUID        NOT NULL,
    source_work_order_id UUID        NOT NULL,
    target_work_order_id UUID        NOT NULL,
    reason               TEXT        NOT NULL,
    linked_by            UUID        NOT NULL,
    linked_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_wo_dup_link        PRIMARY KEY (id),
    CONSTRAINT uq_wo_dup_link_source UNIQUE      (source_work_order_id),
    CONSTRAINT fk_wo_dup_link_source FOREIGN KEY (source_work_order_id) REFERENCES work_order(id),
    CONSTRAINT fk_wo_dup_link_target FOREIGN KEY (target_work_order_id) REFERENCES work_order(id),
    CONSTRAINT chk_wo_dup_no_self    CHECK       (source_work_order_id <> target_work_order_id)
);

CREATE INDEX IF NOT EXISTS idx_wo_dup_link_target
    ON work_order_duplicate_link(target_work_order_id);

-- Envers audit table for work_order_duplicate_link
CREATE TABLE IF NOT EXISTS work_order_duplicate_link_aud (
    id                   UUID     NOT NULL,
    rev                  INTEGER  NOT NULL,
    revtype              SMALLINT,
    source_work_order_id UUID,
    target_work_order_id UUID,
    reason               TEXT,
    linked_by            UUID,
    linked_at            TIMESTAMPTZ,
    CONSTRAINT pk_wo_dup_link_aud     PRIMARY KEY (id, rev),
    CONSTRAINT fk_wo_dup_link_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE INDEX IF NOT EXISTS idx_wo_dup_link_aud_rev
    ON work_order_duplicate_link_aud(rev);
