-- V2__sla_policy.sql
-- Runtime-configurable SLA policy table with per-priority response and resolution targets.
-- Seeded with placeholder rows so no code path ever needs to handle a missing-policy case.

CREATE TABLE sla_policy (
    id                 UUID         NOT NULL,
    priority           VARCHAR(20)  NOT NULL,
    response_minutes   INTEGER      NOT NULL,
    resolution_minutes INTEGER      NOT NULL,
    -- at_risk_fraction: fraction of the SLA window at which a work order is flagged at-risk
    at_risk_fraction   NUMERIC(3,2) NOT NULL DEFAULT 0.80,
    effective_from     TIMESTAMPTZ  NOT NULL,
    effective_to       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_sla_policy         PRIMARY KEY (id),
    CONSTRAINT uq_sla_priority_from  UNIQUE (priority, effective_from),
    CONSTRAINT chk_sla_priority      CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_at_risk_fraction  CHECK (at_risk_fraction > 0 AND at_risk_fraction < 1)
);

-- Seed placeholder SLA rows: one per priority level, effective from 2024-01-01.
-- These are placeholder values — update via a new migration when actual SLAs are ratified.
INSERT INTO sla_policy (id, priority, response_minutes, resolution_minutes, at_risk_fraction, effective_from) VALUES
    ('00000000-0000-7001-8000-000000000001', 'LOW',       240,   480, 0.80, '2024-01-01T00:00:00Z'),
    ('00000000-0000-7001-8000-000000000002', 'MEDIUM',    120,   240, 0.80, '2024-01-01T00:00:00Z'),
    ('00000000-0000-7001-8000-000000000003', 'HIGH',       60,   120, 0.80, '2024-01-01T00:00:00Z'),
    ('00000000-0000-7001-8000-000000000004', 'CRITICAL',   30,    60, 0.80, '2024-01-01T00:00:00Z');
