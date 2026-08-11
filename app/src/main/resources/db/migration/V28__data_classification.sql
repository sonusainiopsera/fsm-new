-- =============================================================================
-- V28: Data classification registry (WO-188)
-- =============================================================================
-- Creates the data_classification metadata table, its Envers audit table,
-- seeds the agreed tier mapping, and adds the PRIVACY_ADMIN role.
--
-- All IDs are UUIDv7 placeholders — application-side generator format:
--   version nibble = 7, variant bits set, time component fixed to epoch
--   prefix dc000000-0000-7000-8000- (d=data-classification, c=row counter block)
--
-- Expand-phase only: no columns dropped, no tables altered destructively.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- data_classification: runtime-configurable tier registry
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_classification (
    id                UUID        NOT NULL,
    module            VARCHAR(100) NOT NULL,
    entity_name       VARCHAR(200) NOT NULL,
    field_name        VARCHAR(200),           -- NULL = entity-level row
    tier              VARCHAR(20)  NOT NULL,
    lawful_basis_note TEXT,
    handling_notes    TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(255),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(255),
    version           INTEGER      NOT NULL DEFAULT 0,

    CONSTRAINT pk_data_classification PRIMARY KEY (id),
    CONSTRAINT chk_data_classification_tier
        CHECK (tier IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);

-- Unique index on (entity_name, field_name) using coalesce to treat NULL as empty string,
-- preventing duplicate entity-level rows while allowing multiple field-level rows.
CREATE UNIQUE INDEX IF NOT EXISTS uq_data_classification_entity_field
    ON data_classification(entity_name, coalesce(field_name, ''));

CREATE INDEX IF NOT EXISTS idx_data_classification_tier
    ON data_classification(tier);

COMMENT ON TABLE data_classification IS
    'Runtime-configurable data classification registry. Tier rows are seeded by migration '
    'and adjustable by PRIVACY_ADMIN via the admin API. Annotated code elements must have '
    'a matching row or the application fails to start (ClassificationConsistencyCheck).';

-- ---------------------------------------------------------------------------
-- data_classification_aud: Hibernate Envers audit table
-- Mirrors all mutable columns; version excluded per do_not_audit_optimistic_locking_field=true
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS data_classification_aud (
    id                UUID        NOT NULL,
    rev               INTEGER     NOT NULL,
    revtype           SMALLINT    NOT NULL,
    module            VARCHAR(100),
    entity_name       VARCHAR(200),
    field_name        VARCHAR(200),
    tier              VARCHAR(20),
    lawful_basis_note TEXT,
    handling_notes    TEXT,
    created_at        TIMESTAMPTZ,
    created_by        VARCHAR(255),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(255),

    CONSTRAINT pk_data_classification_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_data_classification_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE INDEX IF NOT EXISTS idx_data_classification_aud_rev_brin
    ON data_classification_aud USING brin (rev);

-- ---------------------------------------------------------------------------
-- Seed rows (idempotent — ON CONFLICT DO NOTHING)
-- Covers the four classification tiers from the architecture data-flow table.
-- ---------------------------------------------------------------------------

-- RESTRICTED tier: cryptographic material and credentials
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('dc000000-0000-7000-8000-000000000001', 'identity', 'AppUser', 'passwordHash',
     'RESTRICTED',
     'BCrypt hash of user credential — no lawful basis required (not personal data itself, only its hash)',
     'Never log, never export, never include in event payloads. BCrypt cost-12. Width 256.'),

    ('dc000000-0000-7000-8000-000000000002', 'identity', 'RefreshToken', 'tokenHash',
     'RESTRICTED',
     'SHA-256 hash of refresh token handle — stored server-side for reuse detection',
     'Never log, never export. Revoke entire token family on reuse detection.'),

    ('dc000000-0000-7000-8000-000000000003', 'identity', 'AppUser', 'externalSubject',
     'RESTRICTED',
     'OIDC/SAML subject identifier for federated users — constitutes PII under GDPR Art.4',
     'Never log, never export to non-production. Pseudonymise in dev environments.'),

    ('dc000000-0000-7000-8000-000000000004', 'aigateway', 'AiGatewayProperties', 'apiKey',
     'RESTRICTED',
     'Hosted LLM provider API credentials — secret, rotated every 90 days via Secrets Manager',
     'Never log, never in code. Read from Secrets Manager at startup only.')

ON CONFLICT DO NOTHING;

-- CONFIDENTIAL tier: personal and commercially sensitive data
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('dc000000-0000-7000-8000-000000000010', 'domain', 'Customer', NULL,
     'CONFIDENTIAL',
     'Business customer aggregate — contains contact PII. Lawful basis: contract (B2B service agreement).',
     'Role-scoped access. Encrypted at rest. Mask contact fields in logs. Purge 12m after relationship_ended_on.'),

    ('dc000000-0000-7000-8000-000000000011', 'domain', 'Customer', 'primaryContactName',
     'CONFIDENTIAL',
     'Full name of primary business contact — personal data under GDPR Art.4',
     'Never include in event payloads. Mask in logs. Anonymise in non-production.'),

    ('dc000000-0000-7000-8000-000000000012', 'domain', 'Customer', 'primaryContactEmail',
     'CONFIDENTIAL',
     'Email address of primary contact — personal data and direct identifier under GDPR',
     'Never include in event payloads. Mask in logs. Anonymise in non-production.'),

    ('dc000000-0000-7000-8000-000000000013', 'domain', 'Site', NULL,
     'CONFIDENTIAL',
     'Physical service site — location data constitutes personal data when linked to a natural person',
     'Role-scoped access. Access notes may contain security-sensitive information.'),

    ('dc000000-0000-7000-8000-000000000014', 'domain', 'Technician', NULL,
     'CONFIDENTIAL',
     'Field technician profile — links to personal user record. Lawful basis: employment contract.',
     'TECHNICIAN principals see only their own record. Never expose to CUSTOMER role.'),

    ('dc000000-0000-7000-8000-000000000015', 'domain', 'AppUser', 'email',
     'CONFIDENTIAL',
     'User email address — personal data and login identifier. Lawful basis: contract.',
     'Mask in application logs. Anonymise in non-production environments. Unique constraint.')

ON CONFLICT DO NOTHING;

-- INTERNAL tier: operational data
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('dc000000-0000-7000-8000-000000000020', 'domain', 'WorkOrder', NULL,
     'INTERNAL',
     'Work order aggregate — internal operational record. No personal data in body fields.',
     'Role-scoped access. Retain 24 months hot, then archive. Export for audit on request.'),

    ('dc000000-0000-7000-8000-000000000021', 'domain', 'StockLedger', NULL,
     'INTERNAL',
     'Append-only inventory movement log — internal financial and operational data.',
     'Append-only: no UPDATE or DELETE. Retain indefinitely for stock reconciliation.'),

    ('dc000000-0000-7000-8000-000000000022', 'analytics', 'KpiProjectionEntity', NULL,
     'INTERNAL',
     'Aggregated KPI read model — derived from work order and inventory data, no personal data.',
     'Read-only for MANAGER and ADMIN. Aggregate rows only, no individual-level data.')

ON CONFLICT DO NOTHING;

-- PUBLIC tier: reference and catalogue data
INSERT INTO data_classification (id, module, entity_name, field_name, tier, lawful_basis_note, handling_notes)
VALUES
    ('dc000000-0000-7000-8000-000000000030', 'sla', 'SlaPolicy', NULL,
     'PUBLIC',
     'Published SLA deadlines by priority — no personal data, regulatory disclosure.',
     'Cacheable at edge. Admin-configurable. Changes require coordinated deployment.'),

    ('dc000000-0000-7000-8000-000000000031', 'domain', 'Part', NULL,
     'PUBLIC',
     'Spare parts catalogue — publicly available product information.',
     'No access restriction. Cacheable. Available to all authenticated roles.')

ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- PRIVACY_ADMIN role
-- ---------------------------------------------------------------------------

-- Add PRIVACY_ADMIN to the role reference table
INSERT INTO role (id, name)
VALUES ('00000002-0000-7000-8000-000000000006', 'PRIVACY_ADMIN')
ON CONFLICT DO NOTHING;

-- Expand the role_assignment CHECK constraint to include PRIVACY_ADMIN
ALTER TABLE role_assignment DROP CONSTRAINT IF EXISTS chk_role_assignment_name;
ALTER TABLE role_assignment ADD CONSTRAINT chk_role_assignment_name
    CHECK (role_name IN ('ADMIN', 'DISPATCHER', 'TECHNICIAN', 'MANAGER', 'CUSTOMER', 'PRIVACY_ADMIN'));
