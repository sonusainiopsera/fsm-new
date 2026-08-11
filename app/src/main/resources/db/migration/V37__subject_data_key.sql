-- =============================================================================
-- V37: Per-subject envelope encryption infrastructure (WO-193)
--
-- Creates subject_data_key — the persistent registry for wrapped data-encryption
-- keys. The wrapped_key column stores the DEK encrypted by the managed master key;
-- the plaintext DEK never touches the database.
--
-- Expand-phase migrations:
--   1. Create subject_data_key table and indexes.
--   2. Add ciphertext+blind-index columns to technician_position and
--      portal_invitation (nullable so existing rows are not broken).
--   3. Create technician_position_aud for Envers audit (entity gains @Audited).
--   4. Add ciphertext+blind-index columns to portal_invitation_aud (entity
--      loses @NotAudited on contactEmail/contactName).
--
-- NOTE: Existing encrypted columns (latitude, longitude, contact_email_enc,
-- contact_name_enc) retain the legacy EncryptedStringConverter format until a
-- backfill step re-encrypts them under envelope encryption. The converter detects
-- the legacy format automatically during the transition window.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- subject_data_key
-- One row per (subject_type, subject_id, key_version). The production key manager
-- stores the KMS-wrapped DEK in wrapped_key; the LocalStubKeyManager (tests) uses
-- this table optionally.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subject_data_key (
    id             UUID         NOT NULL,
    subject_type   VARCHAR(50)  NOT NULL,
    subject_id     UUID         NOT NULL,
    key_version    INTEGER      NOT NULL,
    wrapped_key    BYTEA        NOT NULL,
    state          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    rotated_at     TIMESTAMPTZ,
    destroyed_at   TIMESTAMPTZ,
    CONSTRAINT pk_subject_data_key PRIMARY KEY (id),
    CONSTRAINT chk_sdk_state CHECK (state IN ('ACTIVE', 'ROTATED', 'DESTROYED')),
    CONSTRAINT uq_sdk_subject_version UNIQUE (subject_type, subject_id, key_version)
);

CREATE INDEX IF NOT EXISTS idx_sdk_subject_id  ON subject_data_key(subject_id);
CREATE INDEX IF NOT EXISTS idx_sdk_state       ON subject_data_key(state);

-- ---------------------------------------------------------------------------
-- technician_position — add envelope ciphertext columns + blind-index columns
-- The existing latitude/longitude columns keep the legacy format during backfill.
-- New _env columns receive the envelope-encrypted values; after backfill the old
-- columns are dropped in a future contract-phase migration.
-- ---------------------------------------------------------------------------
ALTER TABLE technician_position
    ADD COLUMN IF NOT EXISTS latitude_idx  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS longitude_idx VARCHAR(64);

-- Btree indexes on blind-index columns only (equality lookup)
CREATE INDEX IF NOT EXISTS idx_tp_latitude_idx
    ON technician_position(latitude_idx) WHERE latitude_idx IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_tp_longitude_idx
    ON technician_position(longitude_idx) WHERE longitude_idx IS NOT NULL;

-- ---------------------------------------------------------------------------
-- portal_invitation — add blind-index columns
-- contact_email_enc and contact_name_enc already exist (legacy format).
-- ---------------------------------------------------------------------------
ALTER TABLE portal_invitation
    ADD COLUMN IF NOT EXISTS contact_email_idx VARCHAR(64),
    ADD COLUMN IF NOT EXISTS contact_name_idx  VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_pi_contact_email_idx
    ON portal_invitation(contact_email_idx) WHERE contact_email_idx IS NOT NULL;

-- ---------------------------------------------------------------------------
-- technician_position_aud — new audit table (entity gains @Audited in WO-193)
-- Mirrors technician_position columns; encrypted ciphertext persists as-is.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS technician_position_aud (
    id             UUID        NOT NULL,
    rev            INTEGER     NOT NULL,
    revtype        SMALLINT    NOT NULL,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    technician_id  UUID,
    latitude       VARCHAR(512),
    longitude      VARCHAR(512),
    latitude_idx   VARCHAR(64),
    longitude_idx  VARCHAR(64),
    captured_at    TIMESTAMPTZ,
    CONSTRAINT pk_technician_position_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_technician_position_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX IF NOT EXISTS idx_tp_aud_rev_brin
    ON technician_position_aud USING brin (rev);

-- ---------------------------------------------------------------------------
-- portal_invitation_aud — add contact ciphertext + blind-index columns
-- Previously excluded via @NotAudited; now audited so key destruction also
-- renders the audit history unreadable.
-- ---------------------------------------------------------------------------
ALTER TABLE portal_invitation_aud
    ADD COLUMN IF NOT EXISTS contact_email_enc VARCHAR(512),
    ADD COLUMN IF NOT EXISTS contact_name_enc  VARCHAR(512),
    ADD COLUMN IF NOT EXISTS contact_email_idx VARCHAR(64),
    ADD COLUMN IF NOT EXISTS contact_name_idx  VARCHAR(64);
