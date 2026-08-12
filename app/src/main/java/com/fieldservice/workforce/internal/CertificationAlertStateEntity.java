package com.fieldservice.workforce.internal;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Alert state record for a single (certification, stage, validity_key) triple.
 *
 * <p>The unique constraint on (technician_certification_id, alert_stage, validity_key)
 * enforces de-duplication: each stage alerts at most once per certification per
 * validity period. Using {@code expires_on} as the validity_key means re-issuing a
 * certification (new expires_on) automatically resets alerting for the new period.
 *
 * <p>This table is NOT a currency cache — it records that an alert was sent, not
 * whether the certification is current. Currency is always derived from expires_on
 * at query time (WO-023).
 */
@Entity
@Table(name = "certification_alert_state")
class CertificationAlertStateEntity {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "technician_certification_id", nullable = false, updatable = false)
    private UUID technicianCertificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_stage", nullable = false, updatable = false)
    private CertificationAlertStage alertStage;

    /** ISO-8601 date string derived from expires_on, e.g. "2026-09-30". */
    @Column(name = "validity_key", nullable = false, updatable = false)
    private String validityKey;

    @Column(name = "alerted_at", nullable = false, updatable = false)
    private Instant alertedAt;

    protected CertificationAlertStateEntity() {}

    CertificationAlertStateEntity(UUID technicianCertificationId,
                                   CertificationAlertStage alertStage,
                                   String validityKey,
                                   Instant alertedAt) {
        this.technicianCertificationId = technicianCertificationId;
        this.alertStage = alertStage;
        this.validityKey = validityKey;
        this.alertedAt = alertedAt;
    }

    UUID getTechnicianCertificationId() { return technicianCertificationId; }
    CertificationAlertStage getAlertStage()         { return alertStage; }
    String getValidityKey()                          { return validityKey; }
    Instant getAlertedAt()                           { return alertedAt; }
}
