package com.fieldservice.identity.application;

import com.fieldservice.outbox.payload.RefreshTokenReuseDetectedPayload;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.outbox.PiiRedactionUtility;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes security-relevant domain events through the transactional outbox.
 *
 * <p>Events are published within the caller's transaction (propagation MANDATORY on
 * {@link DomainEventPublisher}), so the audit record and SIEM notification cannot exist
 * without the committed state change they describe.
 *
 * <p><strong>RESTRICTED:</strong> No handle, hash, or token material may appear in any
 * event payload or log line emitted by this class.
 */
@Component
public class SecurityEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SecurityEventPublisher.class);

    private final DomainEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public SecurityEventPublisher(DomainEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Publishes a {@code RefreshTokenReuseDetected} critical security event for SIEM alerting.
     *
     * <p>Call this only when the reuse is being detected for the first time (i.e. when the
     * family is not yet revoked). For subsequent reuse attempts on an already-revoked family,
     * use {@link #incrementReuseCounter} instead to avoid flooding the SIEM.
     *
     * @param userId    owner of the compromised family
     * @param familyId  family being revoked
     * @param traceId   request trace identifier (safe to include)
     * @param clientIp  client IP for forensic correlation (not PII per policy)
     * @param userAgent User-Agent header value
     */
    public void publishReuseDetected(UUID userId, UUID familyId, String traceId,
                                     String clientIp, String userAgent) {
        log.warn("security.refresh_reuse_detected userId={} familyId={} traceId={} clientIp={}",
                userId, familyId, traceId, clientIp);

        Map<String, Object> payload = PiiRedactionUtility.toPayloadMap(
                new RefreshTokenReuseDetectedPayload(userId, familyId, traceId, clientIp, userAgent));

        eventPublisher.publish(DomainEvent.of(
                RefreshTokenReuseDetectedPayload.EVENT_TYPE,
                RefreshTokenReuseDetectedPayload.AGGREGATE_TYPE,
                familyId,
                Instant.now(),
                traceId,
                userId,
                payload));

        meterRegistry.counter("auth.refresh_token.reuse_detected",
                "first_detection", "true").increment();
    }

    /**
     * Records a counter-only reuse attempt on an already-revoked family.
     *
     * <p>Does not emit a new outbox event to avoid SIEM flooding when a client
     * repeatedly presents a handle from a previously revoked family.
     */
    public void incrementReuseCounter(UUID familyId, String traceId) {
        log.warn("security.refresh_reuse_revoked_family familyId={} traceId={}", familyId, traceId);
        meterRegistry.counter("auth.refresh_token.reuse_detected",
                "first_detection", "false").increment();
    }
}
