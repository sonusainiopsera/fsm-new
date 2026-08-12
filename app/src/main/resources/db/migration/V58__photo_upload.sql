-- Upload intents: transient per-PUT tracking records.
-- Orphan cleanup deletes unconsumed intents (and their objects) after 24 hours.
CREATE TABLE upload_intent (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    work_order_id UUID        NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    storage_key   TEXT        NOT NULL,
    content_type  TEXT        NOT NULL,
    max_bytes     BIGINT      NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    created_by    UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT upload_intent_storage_key_uq UNIQUE (storage_key)
);

CREATE INDEX idx_upload_intent_work_order ON upload_intent(work_order_id);
-- Partial index drives the orphan-cleanup query efficiently.
CREATE INDEX idx_upload_intent_orphan ON upload_intent(expires_at) WHERE consumed_at IS NULL;

-- Work order photos: permanent Confidential metadata rows.
-- retain_until drives the automated purge job (data classification policy).
CREATE TABLE work_order_photo (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    version       INTEGER     NOT NULL DEFAULT 0,
    work_order_id UUID        NOT NULL REFERENCES work_order(id) ON DELETE CASCADE,
    storage_key   TEXT        NOT NULL,
    category      TEXT        NOT NULL,
    captured_at   TIMESTAMPTZ NOT NULL,
    caption       TEXT,
    retain_until  DATE,
    created_by    UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT work_order_photo_storage_key_uq UNIQUE (storage_key),
    CONSTRAINT work_order_photo_category_chk   CHECK (category IN ('ISSUE', 'COMPLETION'))
);

CREATE INDEX idx_work_order_photo_work_order ON work_order_photo(work_order_id);

-- Envers audit table for work_order_photo.
CREATE TABLE work_order_photo_aud (
    id            UUID        NOT NULL,
    rev           INTEGER     NOT NULL,
    revtype       SMALLINT    NOT NULL,
    version       INTEGER,
    work_order_id UUID,
    storage_key   TEXT,
    category      TEXT,
    captured_at   TIMESTAMPTZ,
    caption       TEXT,
    retain_until  DATE,
    created_by    UUID,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (id, rev),
    CONSTRAINT work_order_photo_aud_rev_fk FOREIGN KEY (rev) REFERENCES revinfo(rev)
);
