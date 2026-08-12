-- V35: Per-subject envelope encryption key registry
-- Creates subject_data_key table for WO-193 per-data-subject envelope encryption.
-- Each row holds a wrapped AES-256 data key for one (subject_type, subject_id, key_version) triple.
-- The plaintext key never appears in this table; wrapped_key holds the ciphertext produced
-- by the master key service (AWS KMS, Azure Key Vault or local stub for dev/test).
--
-- State lifecycle: ACTIVE → ROTATED (on rotate) → DESTROYED (terminal, for lawful erasure WO-095)

CREATE TABLE subject_data_key (
    id           UUID        NOT NULL,
    subject_type VARCHAR(100) NOT NULL,
    subject_id   UUID        NOT NULL,
    key_version  INTEGER     NOT NULL,
    wrapped_key  BYTEA       NOT NULL,
    state        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_sdk_state CHECK (state IN ('ACTIVE', 'ROTATED', 'DESTROYED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rotated_at   TIMESTAMPTZ,
    destroyed_at TIMESTAMPTZ,
    CONSTRAINT pk_subject_data_key PRIMARY KEY (id),
    CONSTRAINT uq_subject_data_key_version
        UNIQUE (subject_type, subject_id, key_version)
);

-- Lookup: resolve by state for rotate/destroy operations
CREATE INDEX idx_sdk_subject_state ON subject_data_key (subject_type, subject_id, state);
