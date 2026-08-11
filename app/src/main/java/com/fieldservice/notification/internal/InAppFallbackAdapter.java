package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Durable in-app fallback adapter.
 *
 * <p>When the circuit breaker is open or the external adapter fails, this adapter:
 * <ol>
 *   <li>Persists an {@link InAppNotificationEntity} row (idempotent — skips if already exists)</li>
 *   <li>Attempts a best-effort SSE push via {@link AlertSseEmitterRegistry}; absent
 *       subscribers are silently tolerated</li>
 * </ol>
 *
 * <p>Restricted to the {@code worker} profile to prevent accidental activation in the
 * API-only tier.
 */
@Component
@Profile("worker")
class InAppFallbackAdapter {

    private static final Logger log = LoggerFactory.getLogger(InAppFallbackAdapter.class);

    private final InAppNotificationRepository repository;
    private final AlertSseEmitterRegistry sseRegistry;

    InAppFallbackAdapter(InAppNotificationRepository repository, AlertSseEmitterRegistry sseRegistry) {
        this.repository = repository;
        this.sseRegistry = sseRegistry;
    }

    InAppNotificationEntity store(NotificationRequest request) {
        if (repository.existsByEventIdAndRecipientUserId(request.eventId(), request.recipientUserId())) {
            log.debug("in_app_fallback idempotent skip eventId={} userId={}",
                    request.eventId(), request.recipientUserId());
            return null;
        }

        InAppNotificationEntity entity = InAppNotificationEntity.create(
                request.recipientUserId(),
                request.eventId(),
                request.category(),
                request.title(),
                request.body(),
                request.severity()
        );
        repository.save(entity);

        log.info("in_app_notification stored id={} eventId={} userId={}",
                entity.getId(), request.eventId(), request.recipientUserId());

        sseRegistry.tryPush(request.recipientUserId(), entity);
        return entity;
    }
}
