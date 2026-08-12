package com.fieldservice.workforce.internal;

import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Writes the {@code certification_alert_state} row and the outbox event in one transaction
 * so alert state and its evidence are always atomic.
 *
 * <p>MANDATORY propagation: must be called inside an existing transaction (the sweep's
 * TransactionTemplate satisfies this requirement).
 */
@Component
class CertificationAlertPublisher {

    static final String EVENT_TYPE_EXPIRING = "CertificationExpiringAlert";
    static final String EVENT_TYPE_EXPIRED  = "CertificationExpiredAlert";
    static final String AGGREGATE_TYPE      = "TECHNICIAN_CERTIFICATION";

    private final CertificationAlertStateRepository alertStateRepo;
    private final DomainEventPublisher              eventPublisher;

    CertificationAlertPublisher(CertificationAlertStateRepository alertStateRepo,
                                 DomainEventPublisher eventPublisher) {
        this.alertStateRepo = alertStateRepo;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Attempts to publish an alert for the given certification and stage.
     *
     * @return {@code true} if a new alert was published, {@code false} if already alerted
     *         for this stage and validity period (idempotent no-op)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean publishIfNotAlerted(UUID certificationId, UUID technicianId,
                                 UUID certificationTypeId, LocalDate expiresOn,
                                 CertificationAlertStage stage, long daysRemaining,
                                 Instant now) {
        String validityKey = expiresOn.toString();

        boolean alreadyAlerted = alertStateRepo
                .findByTechnicianCertificationIdAndAlertStageAndValidityKey(
                        certificationId, stage.name(), validityKey)
                .isPresent();

        if (alreadyAlerted) {
            return false;
        }

        try {
            CertificationAlertStateEntity state = CertificationAlertStateEntity.of(
                    certificationId, stage, validityKey, now);
            alertStateRepo.saveAndFlush(state);

            String eventType = stage == CertificationAlertStage.EXPIRED
                    ? EVENT_TYPE_EXPIRED
                    : EVENT_TYPE_EXPIRING;

            eventPublisher.publish(new DomainEvent(
                    UuidV7.generate(),
                    eventType,
                    AGGREGATE_TYPE,
                    certificationId,
                    now,
                    MDC.get("traceId"),
                    null,
                    new CertificationAlertPayload(
                            certificationId,
                            technicianId,
                            certificationTypeId,
                            expiresOn.toString(),
                            stage.name(),
                            daysRemaining)));
            return true;

        } catch (DataIntegrityViolationException ex) {
            // Concurrent sweep inserted the same alert state row — idempotent no-op
            return false;
        }
    }

    /**
     * Payload for CertificationExpiringAlert and CertificationExpiredAlert outbox events.
     * Contains only identifiers and metadata — no personal data values.
     */
    record CertificationAlertPayload(
            UUID   certificationId,
            UUID   technicianId,
            UUID   certificationTypeId,
            String expiresOn,
            String alertStage,
            long   daysRemaining
    ) {}
}
