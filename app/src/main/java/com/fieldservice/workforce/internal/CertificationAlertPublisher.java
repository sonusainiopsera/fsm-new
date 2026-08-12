package com.fieldservice.workforce.internal;

import com.fieldservice.outbox.payload.CertificationExpiringAlertPayload;
import com.fieldservice.outbox.payload.CertificationExpiredAlertPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Transactional publisher that writes a {@code certification_alert_state} row and its
 * corresponding outbox event in the same transaction (AC-5).
 *
 * <p>Uses {@link Propagation#REQUIRES_NEW} so each certification alert commits
 * independently. A failure for one certification does not roll back alerts already
 * written for others in the same batch (AC-11 edge case — mid-run interruption leaves
 * already-committed batches alerted).
 *
 * <p>De-duplication is enforced structurally by the unique constraint on
 * (technician_certification_id, alert_stage, validity_key). A concurrent duplicate
 * insert raises {@link DataIntegrityViolationException}, which is caught and treated
 * as a successful idempotent no-op (not an error).
 */
@Component
class CertificationAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(CertificationAlertPublisher.class);

    private final CertificationAlertStateRepository alertStateRepository;
    private final DomainEventPublisher eventPublisher;

    CertificationAlertPublisher(CertificationAlertStateRepository alertStateRepository,
                                 DomainEventPublisher eventPublisher) {
        this.alertStateRepository = alertStateRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Writes the alert state row and outbox event for one certification alert.
     *
     * @param certificationId  the {@code technician_certification.id} being alerted
     * @param technicianId     the owning technician (for event routing — not PII)
     * @param typeCode         the certification type code (e.g., "GAS_SAFE")
     * @param expiresOn        the certification expiry date
     * @param stage            the alert stage (WARNING, URGENT, EXPIRED)
     * @param businessDate     the business date used for the sweep
     * @param now              the precise sweep instant (for auditing)
     * @return {@code true} if the alert was newly written; {@code false} if already alerted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean publishAlert(UUID certificationId,
                         UUID technicianId,
                         String typeCode,
                         LocalDate expiresOn,
                         CertificationAlertStage stage,
                         LocalDate businessDate,
                         Instant now) {
        String validityKey = expiresOn.toString();

        // In-memory pre-check avoids an unnecessary save+flush on the hot path.
        // The unique constraint is the structural guarantee; this is just an optimisation.
        if (alertStateRepository.existsByTechnicianCertificationIdAndAlertStageAndValidityKey(
                certificationId, stage, validityKey)) {
            return false;
        }

        CertificationAlertStateEntity state = new CertificationAlertStateEntity(
                certificationId, stage, validityKey, now);

        try {
            alertStateRepository.saveAndFlush(state);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent duplicate — another replica already wrote the same alert state.
            // This is expected at-least-once / concurrent-sweep idempotency.
            log.debug("cert_alert_already_written certId={} stage={}", certificationId, stage);
            return false;
        }

        publishOutboxEvent(certificationId, technicianId, typeCode, expiresOn, stage, businessDate, now);

        log.info("cert_alert_published certId={} technicianId={} stage={} expiresOn={}",
                certificationId, technicianId, stage, expiresOn);
        return true;
    }

    // ── Outbox event helpers ──────────────────────────────────────────────────

    private void publishOutboxEvent(UUID certificationId, UUID technicianId, String typeCode,
                                     LocalDate expiresOn, CertificationAlertStage stage,
                                     LocalDate businessDate, Instant now) {
        long daysToExpiry = ChronoUnit.DAYS.between(businessDate, expiresOn);

        switch (stage) {
            case WARNING, URGENT -> {
                var payload = PiiRedactionUtility.toPayloadMap(new CertificationExpiringAlertPayload(
                        certificationId, technicianId, typeCode,
                        stage.name(), expiresOn, daysToExpiry));
                eventPublisher.publish(DomainEvent.of(
                        CertificationExpiringAlertPayload.EVENT_TYPE,
                        CertificationExpiringAlertPayload.AGGREGATE_TYPE,
                        certificationId, now, null, null, payload));
            }
            case EXPIRED -> {
                long daysExpired = -daysToExpiry;
                var payload = PiiRedactionUtility.toPayloadMap(new CertificationExpiredAlertPayload(
                        certificationId, technicianId, typeCode, expiresOn, daysExpired));
                eventPublisher.publish(DomainEvent.of(
                        CertificationExpiredAlertPayload.EVENT_TYPE,
                        CertificationExpiredAlertPayload.AGGREGATE_TYPE,
                        certificationId, now, null, null, payload));
            }
        }
    }
}
