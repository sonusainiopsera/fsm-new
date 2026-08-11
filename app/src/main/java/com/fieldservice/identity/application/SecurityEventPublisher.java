package com.fieldservice.identity.application;

import com.fieldservice.identity.domain.RefreshTokenFamily;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.util.UuidV7;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes security-relevant token lifecycle events to the transactional outbox.
 *
 * <p>All methods require an active transaction ({@code Propagation.MANDATORY}) so the
 * security event and the triggering state change (revocation, rotation) commit atomically
 * or roll back together — they can never diverge.
 *
 * <p>Payload contract: no handle material (plaintext or hash) appears in any event payload.
 * Only userId, familyId, traceId, clientIp, and userAgent are included for SIEM correlation.
 */
@Component
public class SecurityEventPublisher {

    private final DomainEventPublisher eventPublisher;

    public SecurityEventPublisher(DomainEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes a critical REFRESH_TOKEN_REUSE event for SIEM alerting.
     * Call only once per family — subsequent reuse attempts on an already-revoked
     * family must not trigger a second critical event.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishReuseDetected(RefreshTokenFamily family,
                                     String clientIp,
                                     String userAgent,
                                     String traceId) {
        RefreshTokenReusePayload payload = new RefreshTokenReusePayload(
                family.getUser().getId(),
                family.getId(),
                traceId,
                clientIp,
                userAgent);

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "REFRESH_TOKEN_REUSE",
                "REFRESH_TOKEN_FAMILY",
                family.getId(),
                Instant.now(),
                traceId,
                family.getUser().getId(),
                payload));
    }

    /**
     * Publishes an audit-grade REFRESH_TOKEN_ROTATED event on successful rotation.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishRotationSuccess(RefreshTokenFamily family, UUID userId, String traceId) {
        RefreshTokenRotatedPayload payload = new RefreshTokenRotatedPayload(
                userId,
                family.getId(),
                traceId);

        eventPublisher.publish(new DomainEvent(
                UuidV7.generate(),
                "REFRESH_TOKEN_ROTATED",
                "REFRESH_TOKEN_FAMILY",
                family.getId(),
                Instant.now(),
                traceId,
                userId,
                payload));
    }

    /**
     * Reuse event payload. Contains no handle or hash material.
     * Only the family identifier and correlation fields appear in telemetry.
     */
    record RefreshTokenReusePayload(
            UUID   userId,
            UUID   familyId,
            String traceId,
            String clientIp,
            String userAgent) {}

    record RefreshTokenRotatedPayload(
            UUID   userId,
            UUID   familyId,
            String traceId) {}
}
