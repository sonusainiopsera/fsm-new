package com.fieldservice.workforce.internal;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * De-duplication record for a single certification alert.
 *
 * <p>Keyed on {@code (technician_certification_id, alert_stage, validity_key)} via a
 * unique constraint so each stage fires at most once per validity period. Re-issuing a
 * certification with a new {@code expires_on} produces a new {@code validity_key} and
 * therefore resets alerting without manual intervention.
 */
@Entity
@Table(name = "certification_alert_state")
class CertificationAlertStateEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "technician_certification_id", nullable = false, updatable = false)
    private UUID technicianCertificationId;

    @Column(name = "alert_stage", nullable = false, updatable = false, length = 20)
    private String alertStage;

    /** ISO-8601 date string of {@code expires_on} — forms the validity period key. */
    @Column(name = "validity_key", nullable = false, updatable = false, length = 20)
    private String validityKey;

    @Column(name = "alerted_at", nullable = false, updatable = false)
    private Instant alertedAt;

    protected CertificationAlertStateEntity() {}

    static CertificationAlertStateEntity of(UUID technicianCertificationId,
                                             CertificationAlertStage stage,
                                             String validityKey,
                                             Instant alertedAt) {
        var e = new CertificationAlertStateEntity();
        e.id = UuidV7.generate();
        e.technicianCertificationId = technicianCertificationId;
        e.alertStage = stage.name();
        e.validityKey = validityKey;
        e.alertedAt = alertedAt;
        return e;
    }

    UUID   getId()                          { return id; }
    UUID   getTechnicianCertificationId()   { return technicianCertificationId; }
    String getAlertStage()                  { return alertStage; }
    String getValidityKey()                 { return validityKey; }
    Instant getAlertedAt()                  { return alertedAt; }
}
