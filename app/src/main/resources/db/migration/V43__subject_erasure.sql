-- V43: Cryptographic erasure tombstone for GDPR Right to Erasure (WO-191)
--
-- Design rationale:
--   Erasure is implemented as key destruction, NOT row deletion or null-filling.
--   Envers *_AUD rows are NEVER modified. This table is append-only: it carries
--   NO personal-data values — only identifiers, structural metadata and JSON summaries
--   whose field values are counts and scope names, never PII.
--
-- Outcome states:
--   COMPLETED       — key destruction successful, verification passed
--   IDEMPOTENT_NOOP — subject was already erased; re-run recorded as no-op
--   REFUSED         — legal hold, open assignment or KMS failure; all data intact

CREATE TABLE subject_erasure (
    id                  UUID          NOT NULL,
    dsar_request_id     UUID          NOT NULL
        CONSTRAINT fk_erasure_dsar REFERENCES dsar_request (id),
    subject_type        VARCHAR(100)  NOT NULL,
    subject_id          UUID          NOT NULL,
    key_reference       VARCHAR(255)  NOT NULL,
    erased_at           TIMESTAMPTZ   NOT NULL,
    actor               VARCHAR(255)  NOT NULL,
    -- erased_sections: [{name: string, rowCount: int}]   — no PII values
    erased_sections     JSONB         NOT NULL DEFAULT '[]',
    -- verification_result: [{scope: string, plaintextFound: bool, itemsChecked: int}]
    verification_result JSONB         NOT NULL DEFAULT '[]',
    outcome             VARCHAR(30)   NOT NULL
        CONSTRAINT chk_erasure_outcome
            CHECK (outcome IN ('COMPLETED', 'IDEMPOTENT_NOOP', 'REFUSED')),
    refusal_reason      TEXT,
    CONSTRAINT pk_subject_erasure PRIMARY KEY (id)
);

-- Exactly one COMPLETED erasure per subject — prevents double-write on concurrent run
CREATE UNIQUE INDEX uq_erasure_subject_completed
    ON subject_erasure (subject_type, subject_id)
    WHERE outcome = 'COMPLETED';

CREATE INDEX idx_erasure_subject ON subject_erasure (subject_type, subject_id);
CREATE INDEX idx_erasure_dsar    ON subject_erasure (dsar_request_id);

-- Envers audit table (append-only, Hibernate Envers manages inserts)
CREATE TABLE subject_erasure_aud (
    id                  UUID         NOT NULL,
    REV                 INTEGER      NOT NULL
        CONSTRAINT fk_erasure_aud_rev REFERENCES revinfo (rev),
    REVTYPE             SMALLINT,
    dsar_request_id     UUID,
    subject_type        VARCHAR(100),
    subject_id          UUID,
    key_reference       VARCHAR(255),
    erased_at           TIMESTAMPTZ,
    actor               VARCHAR(255),
    erased_sections     JSONB,
    verification_result JSONB,
    outcome             VARCHAR(30),
    refusal_reason      TEXT,
    CONSTRAINT pk_subject_erasure_aud PRIMARY KEY (id, REV)
);

-- Retention policy: erasure records are retained for 7 years as compliance evidence
INSERT INTO retention_policy (
    id, data_category, entity_name, period_value, period_unit,
    anchor_field, disposal_method, legal_hold, ratified, enabled, notes)
VALUES (
    gen_random_uuid(),
    'SUBJECT_ERASURE_TOMBSTONE',
    'subject_erasure',
    7, 'YEARS',
    'erased_at',
    'CRYPTO_ERASE',
    FALSE, TRUE, TRUE,
    'Erasure tombstone retained 7 years as DPO evidence per GDPR Art. 5(2) accountability.'
) ON CONFLICT DO NOTHING;
