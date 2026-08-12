-- =============================================================================
-- V45: Certification alert state for pre-expiry sweep (WO-120)
--
-- Tracks per-certification per-stage notification state. The validity_key
-- (derived from expires_on) makes re-issue reset alerting structurally:
-- when a certification is re-issued with a later expires_on its new
-- validity_key has no existing row, so alerting restarts automatically.
--
-- De-duplication is enforced by the unique constraint
-- (technician_certification_id, alert_stage, validity_key).
-- =============================================================================

CREATE TABLE certification_alert_state (
    id                          UUID        NOT NULL PRIMARY KEY,
    technician_certification_id UUID        NOT NULL REFERENCES technician_certification(id),
    alert_stage                 TEXT        NOT NULL,
    validity_key                TEXT        NOT NULL,
    alerted_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_cert_alert_stage
        CHECK (alert_stage IN ('WARNING', 'URGENT', 'EXPIRED'))
);

-- De-duplication: each stage alerts at most once per certification per validity period
CREATE UNIQUE INDEX uq_cert_alert_state
    ON certification_alert_state(technician_certification_id, alert_stage, validity_key);

-- Purge index: supports cleanup of old alert state rows by alerted_at
CREATE INDEX idx_cert_alert_alerted_at
    ON certification_alert_state(alerted_at);

-- =============================================================================
-- Scheduler lock table (WO-005 / WO-008 baseline)
-- Only creates if missing — other locks (outbox poller, idempotency purge)
-- may have created this already via DatabaseSchedulingLock.
-- =============================================================================
CREATE TABLE IF NOT EXISTS scheduler_lock (
    lock_name   TEXT        NOT NULL PRIMARY KEY,
    holder      TEXT        NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);
