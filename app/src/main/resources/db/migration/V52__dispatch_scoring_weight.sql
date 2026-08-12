-- =============================================================================
-- V52: Dispatch scoring weight configuration tables
-- =============================================================================
-- Creates:
--   1. dispatch_scoring_weight  — per-factor weight rows with Envers audit.
--      Unique constraint on factor_code. weight >= 0 check.
--      Default workload penalty exponent (super-linear, > 1) documented below.
--   2. dispatch_scoring_config  — scalar configuration entries (e.g. exponent).
--   3. dispatch_scoring_weight_aud / dispatch_scoring_config_aud — Envers tables.
--   4. Seed rows for the four scoring factors and the workload exponent.
-- =============================================================================

-- ── dispatch_scoring_weight ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS dispatch_scoring_weight (
    id          UUID            NOT NULL PRIMARY KEY,
    factor_code TEXT            NOT NULL,
    weight      NUMERIC(6,4)    NOT NULL CHECK (weight >= 0),
    active      BOOLEAN         NOT NULL DEFAULT true,
    version     INTEGER         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_scoring_weight_factor_code UNIQUE (factor_code)
);

-- ── dispatch_scoring_config ─────────────────────────────────────────────────
-- Holds scalar tuning parameters. config_key is the unique identifier.
-- config_value is a plain NUMERIC to avoid type coercion in the loader.
CREATE TABLE IF NOT EXISTS dispatch_scoring_config (
    id           UUID        NOT NULL PRIMARY KEY,
    config_key   TEXT        NOT NULL,
    config_value NUMERIC     NOT NULL,
    version      INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_scoring_config_key UNIQUE (config_key)
);

-- ── Envers audit: dispatch_scoring_weight_aud ───────────────────────────────
CREATE TABLE IF NOT EXISTS dispatch_scoring_weight_aud (
    id          UUID        NOT NULL,
    rev         INTEGER     NOT NULL,
    revtype     SMALLINT    NOT NULL,
    factor_code TEXT,
    weight      NUMERIC(6,4),
    active      BOOLEAN,
    created_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ,
    CONSTRAINT pk_scoring_weight_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_scoring_weight_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- ── Envers audit: dispatch_scoring_config_aud ───────────────────────────────
CREATE TABLE IF NOT EXISTS dispatch_scoring_config_aud (
    id           UUID        NOT NULL,
    rev          INTEGER     NOT NULL,
    revtype      SMALLINT    NOT NULL,
    config_key   TEXT,
    config_value NUMERIC,
    created_at   TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ,
    CONSTRAINT pk_scoring_config_aud PRIMARY KEY (id, rev),
    CONSTRAINT fk_scoring_config_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

-- ── Seed: default factor weights ────────────────────────────────────────────
-- Weights are relative; composite = sum(weight_i * normalised_i) / sum(weight_i).
-- Tuning guidance: competency and travel are the primary differentiators;
-- workload is a soft fairness nudge; parts availability is advisory only.
INSERT INTO dispatch_scoring_weight (id, factor_code, weight, active)
VALUES
  ('c0000052-0001-7000-8000-000000000001', 'COMPETENCY_FIT',     1.0000, true),
  ('c0000052-0002-7000-8000-000000000002', 'TRAVEL_EFFICIENCY',  0.8000, true),
  ('c0000052-0003-7000-8000-000000000003', 'WORKLOAD_FAIRNESS',  0.6000, true),
  ('c0000052-0004-7000-8000-000000000004', 'PARTS_AVAILABILITY', 0.3000, true)
ON CONFLICT (factor_code) DO NOTHING;

-- ── Seed: workload penalty exponent ─────────────────────────────────────────
-- Super-linear exponent > 1 required (AC-4).
-- Default 2.0 (square): a technician 50% above mean receives 0.25 penalty unit;
-- at 100% above mean the penalty is 1.0 (maximum soft penalty).
-- Increase toward 3.0 to push routine work more aggressively toward idle techs.
INSERT INTO dispatch_scoring_config (id, config_key, config_value)
VALUES ('c0000052-0005-7000-8000-000000000005', 'WORKLOAD_PENALTY_EXPONENT', 2.0)
ON CONFLICT (config_key) DO NOTHING;
