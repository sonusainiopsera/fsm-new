-- V55: Photo direct-upload infrastructure.
-- Additive only — no destructive changes to existing tables.
--
-- upload_intent: tracks every presigned PUT issued so registration can be
--   verified and orphan objects identified after 24 hours.
-- work_order_photo: the photo metadata row written after successful registration;
--   binaries live in object storage and are referenced only by storage_key.
-- work_order_photo_aud: Envers audit table for work_order_photo.

CREATE TABLE IF NOT EXISTS upload_intent (
    id              UUID        PRIMARY KEY,
    work_order_id   UUID        NOT NULL,
    storage_key     TEXT        NOT NULL,
    content_type    TEXT        NOT NULL,
    max_bytes       BIGINT      NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_upload_intent_storage_key UNIQUE (storage_key)
);

CREATE INDEX IF NOT EXISTS idx_upload_intent_work_order
    ON upload_intent (work_order_id);

CREATE INDEX IF NOT EXISTS idx_upload_intent_expires
    ON upload_intent (expires_at)
    WHERE consumed_at IS NULL;

-- ---- work_order_photo -------------------------------------------------------

CREATE TABLE IF NOT EXISTS work_order_photo (
    id              UUID        PRIMARY KEY,
    work_order_id   UUID        NOT NULL,
    storage_key     TEXT        NOT NULL,
    category        TEXT        NOT NULL,
    captured_at     TIMESTAMPTZ NOT NULL,
    caption         TEXT        CHECK (char_length(caption) <= 500),
    retain_until    DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID,

    CONSTRAINT uq_work_order_photo_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_work_order_photo_category
        CHECK (category IN ('ISSUE', 'COMPLETION'))
);

CREATE INDEX IF NOT EXISTS idx_work_order_photo_work_order
    ON work_order_photo (work_order_id, captured_at);

-- ---- Envers audit table for work_order_photo --------------------------------
-- revinfo_seq and REVINFO are already created in V5__audit_tables.sql.

CREATE TABLE IF NOT EXISTS work_order_photo_aud (
    id              UUID        NOT NULL,
    REV             INTEGER     NOT NULL,
    REVTYPE         SMALLINT,
    work_order_id   UUID,
    storage_key     TEXT,
    category        TEXT,
    captured_at     TIMESTAMPTZ,
    caption         TEXT,
    retain_until    DATE,
    created_at      TIMESTAMPTZ,
    created_by      UUID,

    CONSTRAINT pk_work_order_photo_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_work_order_photo_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

CREATE INDEX IF NOT EXISTS brin_work_order_photo_aud_rev
    ON work_order_photo_aud USING BRIN (REV);
