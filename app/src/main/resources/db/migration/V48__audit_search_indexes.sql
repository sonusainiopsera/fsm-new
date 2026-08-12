-- V48__audit_search_indexes.sql
-- Audit trail search infrastructure: indexes, audit_export table, and role grants.
-- All changes are expand-only (new objects only — no DROP, no ALTER to existing columns).

-- ============================================================
-- REVINFO search indexes (filter: actor, date range)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_revinfo_revtstmp        ON REVINFO (REVTSTMP DESC);
CREATE INDEX IF NOT EXISTS idx_revinfo_actor_revtstmp  ON REVINFO (actor_user_id, REVTSTMP DESC);

-- ============================================================
-- work_order_aud  (entity-id + rev, rev)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_work_order_aud_id_rev ON work_order_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_work_order_aud_rev    ON work_order_aud (REV);

-- ============================================================
-- assignment_aud
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_assignment_aud_id_rev ON assignment_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_assignment_aud_rev    ON assignment_aud (REV);

-- ============================================================
-- technician_certification_aud
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_tech_cert_aud_id_rev ON technician_certification_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_tech_cert_aud_rev    ON technician_certification_aud (REV);

-- ============================================================
-- app_user_aud
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_app_user_aud_id_rev ON app_user_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_app_user_aud_rev    ON app_user_aud (REV);

-- ============================================================
-- site_aud
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_site_aud_id_rev ON site_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_site_aud_rev    ON site_aud (REV);

-- ============================================================
-- sla_policy_aud
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_sla_policy_aud_id_rev ON sla_policy_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_sla_policy_aud_rev    ON sla_policy_aud (REV);

-- ============================================================
-- role_assignment_aud
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_role_assignment_aud_id_rev ON role_assignment_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_role_assignment_aud_rev    ON role_assignment_aud (REV);

-- ============================================================
-- asset_aud
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_asset_aud_id_rev ON asset_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_asset_aud_rev    ON asset_aud (REV);

-- ============================================================
-- work_order_hold_aud
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_work_order_hold_aud_id_rev ON work_order_hold_aud (id, REV);
CREATE INDEX IF NOT EXISTS idx_work_order_hold_aud_rev    ON work_order_hold_aud (REV);

-- ============================================================
-- audit_export  — tracks synchronous and asynchronous export requests
-- id           : UUIDv7 primary key
-- requested_by : user who requested the export
-- filter_json  : serialised filter parameters (no PII)
-- format       : CSV or JSON
-- status       : QUEUED | GENERATING | COMPLETED | FAILED
-- row_count    : number of rows in the artefact (null until completed)
-- artefact_reference : object-storage key or inline download token
-- requested_at / completed_at : lifecycle timestamps
-- ============================================================
CREATE TABLE audit_export (
    id                   UUID         NOT NULL,
    requested_by         UUID         NOT NULL,
    filter_json          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    format               TEXT         NOT NULL,
    status               TEXT         NOT NULL DEFAULT 'QUEUED',
    row_count            INTEGER,
    artefact_reference   TEXT,
    failure_reason       TEXT,
    requested_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at         TIMESTAMPTZ,
    CONSTRAINT pk_audit_export PRIMARY KEY (id),
    CONSTRAINT ck_audit_export_format CHECK (format IN ('CSV', 'JSON')),
    CONSTRAINT ck_audit_export_status CHECK (status IN ('QUEUED', 'GENERATING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_audit_export_requested_at ON audit_export (requested_at DESC);
CREATE INDEX idx_audit_export_requested_by ON audit_export (requested_by, requested_at DESC);

-- ============================================================
-- Note: revinfo_seq was declared in V5__audit_tables.sql.
-- This migration confirms it exists for release-gate assertions.
-- ============================================================
-- (No CREATE SEQUENCE here — V5 already owns it.)
