-- V44: certification_alert_state — de-duplication store for the certification expiry alert sweep.
--
-- De-duplication key: (technician_certification_id, alert_stage, validity_key).
-- validity_key is the ISO-8601 text representation of expires_on so that re-issuing a
-- certification with a new expires_on automatically resets alerting without manual cleanup.
--
-- No changes to certification tables (currency remains query-time only per AC-3).

CREATE TABLE certification_alert_state (
    id                           UUID        NOT NULL,
    technician_certification_id  UUID        NOT NULL
        REFERENCES technician_certification(id) ON DELETE CASCADE,
    alert_stage                  TEXT        NOT NULL
        CHECK (alert_stage IN ('WARNING', 'URGENT', 'EXPIRED')),
    validity_key                 TEXT        NOT NULL,
    alerted_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_certification_alert_state PRIMARY KEY (id),
    CONSTRAINT uq_cert_alert_stage_validity
        UNIQUE (technician_certification_id, alert_stage, validity_key)
);

-- For purge queries keyed on time
CREATE INDEX idx_cert_alert_state_alerted_at
    ON certification_alert_state (alerted_at);
