-- V33__retention_policy.sql
-- Creates retention_policy and purge_run tables with Hibernate Envers AUD tables.
-- Seeded with 5 placeholder retention policy rows (ratified=false) for the
-- five indicative categories from BR-25 / Q7.  Only seed data depends on the
-- ratified periods — no code changes required once Q7 is resolved.
-- Expand-only: no destructive changes.

-- ============================================================
-- retention_policy
-- One row per data category.  Unique on data_category.
-- period_value + period_unit express the retention window so
-- month and year semantics are preserved (not raw minutes).
-- ============================================================
CREATE TABLE retention_policy (
    id              UUID         NOT NULL,
    data_category   VARCHAR(100) NOT NULL,
    entity_name     VARCHAR(255),
    period_value    INTEGER      NOT NULL,
    period_unit     VARCHAR(10)  NOT NULL,
    anchor_field    VARCHAR(255) NOT NULL,
    disposal_method VARCHAR(20)  NOT NULL,
    legal_hold      BOOLEAN      NOT NULL DEFAULT FALSE,
    ratified        BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(255),
    version         INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_retention_policy                PRIMARY KEY (id),
    CONSTRAINT uq_retention_policy_category       UNIQUE (data_category),
    CONSTRAINT chk_retention_policy_period_value  CHECK (period_value > 0),
    CONSTRAINT chk_retention_policy_period_unit   CHECK (period_unit IN ('DAYS', 'MONTHS', 'YEARS')),
    CONSTRAINT chk_retention_policy_disposal      CHECK (disposal_method IN ('PHYSICAL_DELETE', 'CRYPTO_ERASE'))
);

-- ============================================================
-- retention_policy_aud (Hibernate Envers)
-- ============================================================
CREATE TABLE retention_policy_aud (
    id              UUID         NOT NULL,
    REV             INTEGER      NOT NULL,
    REVTYPE         SMALLINT,
    data_category   VARCHAR(100),
    entity_name     VARCHAR(255),
    period_value    INTEGER,
    period_unit     VARCHAR(10),
    anchor_field    VARCHAR(255),
    disposal_method VARCHAR(20),
    legal_hold      BOOLEAN,
    ratified        BOOLEAN,
    enabled         BOOLEAN,
    notes           TEXT,
    created_at      TIMESTAMPTZ,
    created_by      VARCHAR(255),
    updated_at      TIMESTAMPTZ,
    updated_by      VARCHAR(255),
    version         INTEGER,
    CONSTRAINT pk_retention_policy_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_retention_policy_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- purge_run
-- Append-only audit record for every sweep execution (including
-- zero-disposal runs).  No UPDATE or DELETE is ever issued on
-- this table by the application.
-- ============================================================
CREATE TABLE purge_run (
    id              UUID         NOT NULL,
    data_category   VARCHAR(100) NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    cutoff_instant  TIMESTAMPTZ,
    rows_examined   BIGINT       NOT NULL DEFAULT 0,
    rows_disposed   BIGINT       NOT NULL DEFAULT 0,
    rows_skipped    BIGINT       NOT NULL DEFAULT 0,
    skip_reasons    JSONB,
    outcome         VARCHAR(50),
    error_code      VARCHAR(100),
    CONSTRAINT pk_purge_run PRIMARY KEY (id)
);

-- Efficient time-range queries per category (descending to surface latest run first)
CREATE INDEX idx_purge_run_category_started
    ON purge_run (data_category, started_at DESC);

-- ============================================================
-- purge_run_aud (Hibernate Envers)
-- ============================================================
CREATE TABLE purge_run_aud (
    id              UUID         NOT NULL,
    REV             INTEGER      NOT NULL,
    REVTYPE         SMALLINT,
    data_category   VARCHAR(100),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    cutoff_instant  TIMESTAMPTZ,
    rows_examined   BIGINT,
    rows_disposed   BIGINT,
    rows_skipped    BIGINT,
    skip_reasons    JSONB,
    outcome         VARCHAR(50),
    error_code      VARCHAR(100),
    CONSTRAINT pk_purge_run_aud     PRIMARY KEY (id, REV),
    CONSTRAINT fk_purge_run_aud_rev FOREIGN KEY (REV) REFERENCES REVINFO (REV)
);

-- ============================================================
-- Seed: 5 indicative retention policy rows — all ratified=false.
-- Notes explicitly state these are placeholder values pending Q7.
-- UUID prefix: 00000000-0000-7033-8000-XXXXXXXXXXXX
-- ============================================================
INSERT INTO retention_policy
    (id, data_category, entity_name, period_value, period_unit, anchor_field,
     disposal_method, legal_hold, ratified, enabled, notes)
VALUES
    ('00000000-0000-7033-8000-000000000001',
     'LOCATION_TRACES', 'TechnicianPosition',
     90, 'DAYS', 'captured_at', 'PHYSICAL_DELETE', FALSE, FALSE, FALSE,
     'PLACEHOLDER — value pending Q7 DPO ratification. Indicative: 90 days per BR-25. '
     'Do not present this value to users or auditors as a ratified target.'),

    ('00000000-0000-7033-8000-000000000002',
     'SITE_PHOTOGRAPHS', 'WorkOrderPhoto',
     24, 'MONTHS', 'created_at', 'PHYSICAL_DELETE', FALSE, FALSE, FALSE,
     'PLACEHOLDER — value pending Q7 DPO ratification. Indicative: 24 months per BR-25. '
     'Object-storage delete must follow database row deletion. '
     'Do not present this value to users or auditors as a ratified target.'),

    ('00000000-0000-7033-8000-000000000003',
     'CLOSED_WORK_ORDERS', 'WorkOrder',
     5, 'YEARS', 'closed_at', 'PHYSICAL_DELETE', FALSE, FALSE, FALSE,
     'PLACEHOLDER — value pending Q7 DPO ratification. Indicative: 5 years per BR-25. '
     'Deletion must cascade to labour_entry, assignment, work_order_part and hold records. '
     'Do not present this value to users or auditors as a ratified target.'),

    ('00000000-0000-7033-8000-000000000004',
     'AUDIT_RECORDS', 'AppRevision',
     24, 'MONTHS', 'created_at', 'PHYSICAL_DELETE', FALSE, FALSE, FALSE,
     'PLACEHOLDER — value pending Q7 DPO ratification. Indicative: 24 months per BR-25. '
     'The one-year audit retention floor (BR-25) is enforced by PurgeSweepJob regardless of this value; '
     'a policy specifying < 12 months for this category is rejected with 422. '
     'Do not present this value to users or auditors as a ratified target.'),

    ('00000000-0000-7033-8000-000000000005',
     'CUSTOMER_ACCOUNTS', 'CustomerAccount',
     12, 'MONTHS', 'deactivated_at', 'PHYSICAL_DELETE', FALSE, FALSE, FALSE,
     'PLACEHOLDER — value pending Q7 DPO ratification. Indicative: 12 months after relationship end per BR-25. '
     'anchor_field deactivated_at: retention runs from the date the customer relationship ended. '
     'Do not present this value to users or auditors as a ratified target.')
ON CONFLICT DO NOTHING;
