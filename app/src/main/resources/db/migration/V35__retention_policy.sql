-- =============================================================================
-- V35: Retention policy and purge run tables (WO-189)
--
-- retention_policy: runtime-configurable schedule of how long each data category
--   is retained before automated purge; seeded with indicative placeholder values
--   pending Q7 DPO ratification (all rows have ratified=false at seed time).
--
-- purge_run: append-only audit record for every sweep execution; never updated
--   or deleted once written; Envers-audited.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- retention_policy
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS retention_policy (
    id              UUID         NOT NULL,
    data_category   VARCHAR(100) NOT NULL,
    entity_name     VARCHAR(200) NOT NULL,
    period_value    INTEGER      NOT NULL CHECK (period_value > 0),
    period_unit     VARCHAR(10)  NOT NULL CHECK (period_unit IN ('DAYS', 'MONTHS', 'YEARS')),
    anchor_field    VARCHAR(100) NOT NULL,
    disposal_method VARCHAR(20)  NOT NULL CHECK (disposal_method IN ('PHYSICAL_DELETE', 'CRYPTO_ERASE')),
    legal_hold      BOOLEAN      NOT NULL DEFAULT false,
    ratified        BOOLEAN      NOT NULL DEFAULT false,
    enabled         BOOLEAN      NOT NULL DEFAULT false,
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_retention_policy PRIMARY KEY (id),
    CONSTRAINT uq_retention_policy_data_category UNIQUE (data_category)
);

-- Partial index for the common worker query: enabled + ratified + not on legal hold
CREATE INDEX IF NOT EXISTS idx_retention_policy_active
    ON retention_policy (data_category)
    WHERE enabled = true AND ratified = true AND legal_hold = false;

-- ---------------------------------------------------------------------------
-- purge_run — append-only; never updated after initial INSERT
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS purge_run (
    id              UUID         NOT NULL,
    data_category   VARCHAR(100) NOT NULL,
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ,
    cutoff_instant  TIMESTAMPTZ  NOT NULL,
    rows_examined   BIGINT       NOT NULL DEFAULT 0,
    rows_disposed   BIGINT       NOT NULL DEFAULT 0,
    rows_skipped    BIGINT       NOT NULL DEFAULT 0,
    skip_reasons    JSONB,
    outcome         VARCHAR(30)  NOT NULL,
    error_code      VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_purge_run PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_purge_run_category_started
    ON purge_run (data_category, started_at DESC);

-- ---------------------------------------------------------------------------
-- Envers audit mirrors — required before application startup (ddl-auto=validate)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS retention_policy_aud (
    id              UUID         NOT NULL,
    rev             INTEGER      NOT NULL,
    revtype         SMALLINT     NOT NULL,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    data_category   VARCHAR(100),
    entity_name     VARCHAR(200),
    period_value    INTEGER,
    period_unit     VARCHAR(10),
    anchor_field    VARCHAR(100),
    disposal_method VARCHAR(20),
    legal_hold      BOOLEAN,
    ratified        BOOLEAN,
    enabled         BOOLEAN,
    notes           TEXT,
    CONSTRAINT pk_retention_policy_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_retention_policy_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX IF NOT EXISTS idx_retention_policy_aud_rev_brin
    ON retention_policy_aud USING brin (rev);

CREATE TABLE IF NOT EXISTS purge_run_aud (
    id              UUID         NOT NULL,
    rev             INTEGER      NOT NULL,
    revtype         SMALLINT     NOT NULL,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    data_category   VARCHAR(100),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    cutoff_instant  TIMESTAMPTZ,
    rows_examined   BIGINT,
    rows_disposed   BIGINT,
    rows_skipped    BIGINT,
    skip_reasons    JSONB,
    outcome         VARCHAR(30),
    error_code      VARCHAR(100),
    CONSTRAINT pk_purge_run_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_purge_run_aud_rev FOREIGN KEY (rev) REFERENCES revinfo (rev)
);

CREATE INDEX IF NOT EXISTS idx_purge_run_aud_rev_brin
    ON purge_run_aud USING brin (rev);

-- ---------------------------------------------------------------------------
-- Seed data — five indicative categories, ALL with ratified=false
-- Notes explicitly state these are placeholders pending Q7 DPO ratification.
-- No indicative number must be presented anywhere in the UI or API as ratified.
-- ---------------------------------------------------------------------------
INSERT INTO retention_policy (
    id, data_category, entity_name, period_value, period_unit,
    anchor_field, disposal_method, legal_hold, ratified, enabled, notes, version
) VALUES
    (
        'cc000000-0000-7000-8000-000000000001',
        'LOCATION_TRACES',
        'GpsPosition',
        90, 'DAYS',
        'created_at',
        'PHYSICAL_DELETE',
        false, false, false,
        'PLACEHOLDER — indicative 90 days, pending Q7 DPO ratification. Must not be presented as a ratified retention target.',
        0
    ),
    (
        'cc000000-0000-7000-8000-000000000002',
        'SITE_PHOTOGRAPHS',
        'WorkOrderPhoto',
        24, 'MONTHS',
        'created_at',
        'PHYSICAL_DELETE',
        false, false, false,
        'PLACEHOLDER — indicative 24 months, pending Q7 DPO ratification. Must not be presented as a ratified retention target.',
        0
    ),
    (
        'cc000000-0000-7000-8000-000000000003',
        'CLOSED_WORK_ORDERS',
        'WorkOrder',
        5, 'YEARS',
        'updated_at',
        'PHYSICAL_DELETE',
        false, false, false,
        'PLACEHOLDER — indicative 5 years, pending Q7 DPO ratification. Must not be presented as a ratified retention target.',
        0
    ),
    (
        'cc000000-0000-7000-8000-000000000004',
        'AUDIT_RECORDS',
        'AuditRevisionEntity',
        24, 'MONTHS',
        'revtstmp',
        'PHYSICAL_DELETE',
        false, false, false,
        'PLACEHOLDER — indicative 24 months, pending Q7 DPO ratification. Service enforces a 1-year audit retention floor: any policy shorter than 12 months is refused with 422. Must not be presented as a ratified retention target.',
        0
    ),
    (
        'cc000000-0000-7000-8000-000000000005',
        'CUSTOMER_ACCOUNTS',
        'Customer',
        12, 'MONTHS',
        'relationship_end_date',
        'PHYSICAL_DELETE',
        false, false, false,
        'PLACEHOLDER — indicative 12 months after relationship end, pending Q7 DPO ratification. Must not be presented as a ratified retention target.',
        0
    )
ON CONFLICT (data_category) DO NOTHING;
