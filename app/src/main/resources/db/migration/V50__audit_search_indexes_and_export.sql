-- =============================================================================
-- V50: Audit search indexes and audit_export table (WO-199)
-- Additive-only: new indexes and one new table.
--
-- Adds search-support indexes on REVINFO and the per-entity _AUD tables so that
-- the audit search API does not perform full table scans.
-- Adds audit_export to track synchronous and asynchronous export requests.
-- =============================================================================

-- ── REVINFO indexes ────────────────────────────────────────────────────────────

-- Primary search ordering: newest revisions first
CREATE INDEX IF NOT EXISTS idx_revinfo_rev_tstmp_desc
    ON revinfo (rev_tstmp DESC);

-- Actor lookup (filter by acting user)
CREATE INDEX IF NOT EXISTS idx_revinfo_actor_user_id
    ON revinfo (actor_user_id)
    WHERE actor_user_id IS NOT NULL;

-- ── work_order_aud indexes ─────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_work_order_aud_id_rev
    ON work_order_aud (id, rev);

CREATE INDEX IF NOT EXISTS idx_work_order_aud_rev
    ON work_order_aud (rev);

-- ── app_user_aud indexes ───────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_app_user_aud_id_rev
    ON app_user_aud (id, rev);

CREATE INDEX IF NOT EXISTS idx_app_user_aud_rev
    ON app_user_aud (rev);

-- ── site_aud indexes ───────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_site_aud_id_rev
    ON site_aud (id, rev);

CREATE INDEX IF NOT EXISTS idx_site_aud_rev
    ON site_aud (rev);

-- ── assignment_aud indexes ─────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_assignment_aud_id_rev
    ON assignment_aud (id, rev);

CREATE INDEX IF NOT EXISTS idx_assignment_aud_rev
    ON assignment_aud (rev);

-- ── sla_policy_aud indexes ─────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS idx_sla_policy_aud_id_rev
    ON sla_policy_aud (id, rev);

CREATE INDEX IF NOT EXISTS idx_sla_policy_aud_rev
    ON sla_policy_aud (rev);

-- ── audit_export table ─────────────────────────────────────────────────────────
-- Tracks export requests and their lifecycle (PENDING → COMPLETED | FAILED).
-- Export artefacts are never stored in the database; only a short-lived
-- reference is recorded after asynchronous generation.

CREATE TABLE IF NOT EXISTS audit_export (
    id                  UUID            NOT NULL PRIMARY KEY,
    requested_by        UUID            NOT NULL,
    filter_json         JSONB           NOT NULL DEFAULT '{}'::jsonb,
    format              VARCHAR(4)      NOT NULL,
    status              VARCHAR(12)     NOT NULL DEFAULT 'PENDING',
    row_count           INTEGER,
    artefact_reference  TEXT,
    failure_reason      TEXT,
    requested_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,

    CONSTRAINT chk_audit_export_format
        CHECK (format IN ('CSV', 'JSON')),
    CONSTRAINT chk_audit_export_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_audit_export_requested_at_desc
    ON audit_export (requested_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_export_requested_by
    ON audit_export (requested_by);

-- Confirm revinfo_seq exists (release-gate check helper)
-- The sequence was declared in V5; this is a no-op assertion migration.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_sequences WHERE schemaname = 'public' AND sequencename = 'revinfo_seq'
    ) THEN
        RAISE EXCEPTION 'revinfo_seq not found — Envers will fail at runtime. Check V5 migration.';
    END IF;
END $$;
