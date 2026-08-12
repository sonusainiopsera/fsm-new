-- V50: Dispatch scoring weights and configuration
-- Holds runtime-tunable weights for the four scoring factors and the workload
-- penalty exponent. Both tables are Envers-audited so every tuning change is
-- attributable to an actor and time-stamped.

-- ── dispatch_scoring_weight ────────────────────────────────────────────────
-- One row per factor code. weight >= 0 enforced by check constraint.
-- Optimistic-lock version column prevents blind overwrites during concurrent tuning.
CREATE TABLE dispatch_scoring_weight (
    id          UUID            NOT NULL DEFAULT gen_random_uuid(),
    factor_code TEXT            NOT NULL,
    weight      NUMERIC(6,4)    NOT NULL,
    active      BOOLEAN         NOT NULL DEFAULT TRUE,
    version     INTEGER         NOT NULL DEFAULT 0,
    CONSTRAINT pk_dispatch_scoring_weight   PRIMARY KEY (id),
    CONSTRAINT uq_dispatch_scoring_weight_code UNIQUE (factor_code),
    CONSTRAINT chk_dispatch_scoring_weight_pos CHECK (weight >= 0)
);

-- ── dispatch_scoring_config ────────────────────────────────────────────────
-- Key-value store for scalar configuration (currently: workload exponent).
-- Default exponent = 1.5 (super-linear but moderate; documented here so
-- the business can tune it empirically after launch).
CREATE TABLE dispatch_scoring_config (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    config_key   TEXT         NOT NULL,
    config_value NUMERIC(8,4) NOT NULL,
    version      INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT pk_dispatch_scoring_config    PRIMARY KEY (id),
    CONSTRAINT uq_dispatch_scoring_config_key UNIQUE (config_key)
);

-- ── Envers audit tables ────────────────────────────────────────────────────
CREATE TABLE dispatch_scoring_weight_aud (
    id          UUID        NOT NULL,
    rev         INTEGER     NOT NULL,
    revtype     SMALLINT,
    factor_code TEXT,
    weight      NUMERIC(6,4),
    active      BOOLEAN,
    version     INTEGER,
    CONSTRAINT pk_dispatch_scoring_weight_aud     PRIMARY KEY (id, rev),
    CONSTRAINT fk_dispatch_scoring_weight_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE TABLE dispatch_scoring_config_aud (
    id           UUID         NOT NULL,
    rev          INTEGER      NOT NULL,
    revtype      SMALLINT,
    config_key   TEXT,
    config_value NUMERIC(8,4),
    version      INTEGER,
    CONSTRAINT pk_dispatch_scoring_config_aud     PRIMARY KEY (id, rev),
    CONSTRAINT fk_dispatch_scoring_config_aud_rev FOREIGN KEY (rev) REFERENCES revinfo(rev)
);

CREATE INDEX idx_dispatch_scoring_weight_aud_rev ON dispatch_scoring_weight_aud(rev);
CREATE INDEX idx_dispatch_scoring_config_aud_rev ON dispatch_scoring_config_aud(rev);

-- ── Seed default weights ───────────────────────────────────────────────────
-- Weights sum to 1.0 for intuitive percentages; engine normalises anyway.
-- COMPETENCY_FIT    0.35  — primary hard-skills signal
-- TRAVEL_EFFICIENCY 0.30  — distance cost is high in field service
-- WORKLOAD_FAIRNESS 0.25  — fairness/utilisation balance
-- PARTS_AVAILABILITY 0.10 — advisory soft signal only
INSERT INTO dispatch_scoring_weight (id, factor_code, weight, active, version)
VALUES
    ('00000000-0000-7034-0001-000000000001', 'COMPETENCY_FIT',     0.3500, TRUE, 0),
    ('00000000-0000-7034-0001-000000000002', 'TRAVEL_EFFICIENCY',  0.3000, TRUE, 0),
    ('00000000-0000-7034-0001-000000000003', 'WORKLOAD_FAIRNESS',  0.2500, TRUE, 0),
    ('00000000-0000-7034-0001-000000000004', 'PARTS_AVAILABILITY', 0.1000, TRUE, 0)
ON CONFLICT (factor_code) DO NOTHING;

-- Workload exponent — must be > 1.0 for super-linear penalty behaviour.
-- Default 1.5: a technician at 2x team mean contributes only (2x-1)^1.5 ≈ 1.0 penalty,
-- which is meaningful but does not dominate over a good competency fit.
INSERT INTO dispatch_scoring_config (id, config_key, config_value, version)
VALUES
    ('00000000-0000-7034-0002-000000000001', 'WORKLOAD_EXPONENT', 1.5000, 0)
ON CONFLICT (config_key) DO NOTHING;
