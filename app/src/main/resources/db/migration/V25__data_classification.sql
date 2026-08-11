-- V25__data_classification.sql
-- Creates the data_classification registry table with Hibernate Envers audit table.
-- Extends role_assignment CHECK constraint to include PRIVACY_ADMIN.
-- Seeds tier rows for the agreed data-flow classification taxonomy.
-- Expand-only: no destructive changes.

-- ============================================================
-- data_classification
-- Primary key: UUIDv7 (application-side, never gen_random_uuid).
-- Unique index on (entity_name, COALESCE(field_name, '')) ensures at most one
-- entity-level row (field_name NULL) per entity and at most one field-level row
-- per (entity, field) pair.  Standard UNIQUE on nullable field_name would allow
-- multiple NULL rows for the same entity, so we use a functional index.
-- ============================================================
CREATE TABLE data_classification (
    id                UUID         NOT NULL,
    module            VARCHAR(100) NOT NULL,
    entity_name       VARCHAR(255) NOT NULL,
    field_name        VARCHAR(255),
    tier              VARCHAR(20)  NOT NULL,
    lawful_basis_note TEXT,
    handling_notes    TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(255),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(255),
    version           INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_data_classification      PRIMARY KEY (id),
    CONSTRAINT chk_data_classification_tier CHECK (tier IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);

CREATE UNIQUE INDEX uq_data_classification_entity_field
    ON data_classification (entity_name, COALESCE(field_name, ''));

CREATE INDEX idx_data_classification_tier ON data_classification (tier);

-- ============================================================
-- data_classification_aud (Hibernate Envers)
-- Mirrors data_classification; all columns nullable for DEL revisions.
-- ============================================================
CREATE TABLE data_classification_aud (
    id                UUID         NOT NULL,
    REV               INTEGER      NOT NULL,
    REVTYPE           SMALLINT,
    module            VARCHAR(100),
    entity_name       VARCHAR(255),
    field_name        VARCHAR(255),
    tier              VARCHAR(20),
    lawful_basis_note TEXT,
    handling_notes    TEXT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(255),
    version           INTEGER,
    CONSTRAINT pk_data_classification_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_data_classification_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- Extend role_assignment CHECK to include PRIVACY_ADMIN.
-- Expand-only: only adds a value, never removes an existing one.
-- ============================================================
ALTER TABLE role_assignment
    DROP CONSTRAINT chk_role_assignment_role,
    ADD  CONSTRAINT chk_role_assignment_role CHECK (role_name IN (
        'ADMIN', 'DISPATCHER', 'TECHNICIAN', 'MANAGER', 'CUSTOMER', 'PRIVACY_ADMIN'
    ));

-- ============================================================
-- Seed: RESTRICTED tier
-- Credential and cryptographic material that must never appear in logs or events.
-- ============================================================
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('00000000-0000-7025-8000-000000000001', 'identity',  'AppUser',      'passwordHash',  'RESTRICTED',
     'Security credential — required for authentication',
     'BCrypt hash; @NotAudited in Envers; excluded from event payloads and logs.'),
    ('00000000-0000-7025-8000-000000000002', 'identity',  'RefreshToken', 'tokenHash',     'RESTRICTED',
     'Security credential — required for session continuity',
     'SHA-256 hex of opaque handle; stored only; never transmitted in plaintext.'),
    ('00000000-0000-7025-8000-000000000003', 'identity',  'SigningKey',   'privateKeyPem', 'RESTRICTED',
     'JWT signing infrastructure',
     'Asymmetric private key; sourced from environment variable, never persisted.'),
    ('00000000-0000-7025-8000-000000000004', 'aigateway', 'ProviderConfig', 'apiKey',      'RESTRICTED',
     'Third-party API credential',
     'Provider API key; sourced from environment variable, never persisted.')
ON CONFLICT DO NOTHING;

-- ============================================================
-- Seed: CONFIDENTIAL tier
-- Personal data or commercially sensitive data requiring access control.
-- ============================================================
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('00000000-0000-7025-8000-000000000010', 'customer',   'CustomerAccount', null,          'CONFIDENTIAL',
     'GDPR Art. 6(1)(b) — contract performance with the customer',
     'Customer record contains personal and business contact data; staff access only.'),
    ('00000000-0000-7025-8000-000000000011', 'site',       'Site',            null,          'CONFIDENTIAL',
     'GDPR Art. 6(1)(b) — contract performance',
     'Site address, contact name and access notes; restrict bulk export.'),
    ('00000000-0000-7025-8000-000000000016', 'technician', 'Technician',      null,          'CONFIDENTIAL',
     'GDPR Art. 6(1)(b) — employment contract',
     'Technician entity contains personal data; restrict access to staff roles.'),
    ('00000000-0000-7025-8000-000000000012', 'technician', 'Technician',      'fullName',    'CONFIDENTIAL',
     'GDPR Art. 6(1)(b) — employment contract',
     'Technician personal name; restrict logging and external disclosure.'),
    ('00000000-0000-7025-8000-000000000013', 'technician', 'Technician',      'phone',       'CONFIDENTIAL',
     'GDPR Art. 6(1)(b) — employment contract',
     'Mobile phone number; restrict logging and external disclosure.'),
    ('00000000-0000-7025-8000-000000000017', 'identity',   'AppUser',         null,          'CONFIDENTIAL',
     'GDPR Art. 6(1)(b) — account management',
     'AppUser entity contains personal data including email and display name.'),
    ('00000000-0000-7025-8000-000000000014', 'identity',   'AppUser',         'email',       'CONFIDENTIAL',
     'GDPR Art. 6(1)(b) — account management',
     'Email address used for authentication and notifications.'),
    ('00000000-0000-7025-8000-000000000015', 'identity',   'AppUser',         'displayName', 'CONFIDENTIAL',
     'GDPR Art. 6(1)(b) — account management',
     'Human-readable name displayed in UI; do not log in plaintext.')
ON CONFLICT DO NOTHING;

-- ============================================================
-- Seed: INTERNAL tier
-- Operational data for internal staff only; no direct personal data.
-- ============================================================
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('00000000-0000-7025-8000-000000000020', 'workorder',  'WorkOrder',      null, 'INTERNAL',
     'Legitimate business interest — operational service records',
     'Work order data; internal staff access only. No customer PII in aggregate payload.'),
    ('00000000-0000-7025-8000-000000000021', 'inventory',  'StockLedger',    null, 'INTERNAL',
     'Legitimate business interest — stock management',
     'Stock movement records; ADMIN, DISPATCHER, MANAGER access.'),
    ('00000000-0000-7025-8000-000000000022', 'analytics',  'KpiProjection',  null, 'INTERNAL',
     'Legitimate business interest — operational analytics',
     'Aggregated KPI data; derived, no individual PII.')
ON CONFLICT DO NOTHING;

-- ============================================================
-- Seed: PUBLIC tier
-- Published externally; no handling restrictions.
-- ============================================================
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('00000000-0000-7025-8000-000000000030', 'sla',       'SlaPolicy', null, 'PUBLIC',
     'Published service commitment',
     'SLA response and resolution targets published in customer service agreements.'),
    ('00000000-0000-7025-8000-000000000031', 'inventory', 'Part',      null, 'PUBLIC',
     'Published product catalogue',
     'Parts catalogue with SKU, name and list price; no personal data.')
ON CONFLICT DO NOTHING;
