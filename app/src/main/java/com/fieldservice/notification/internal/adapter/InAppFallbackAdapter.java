package com.fieldservice.notification.internal.adapter;

import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.internal.InAppNotificationEntity;
import com.fieldservice.notification.internal.InAppNotificationRepository;
import com.fieldservice.notification.internal.SseEmitterRegistry;
import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Durable fallback adapter. Persists an {@code in_app_notification} row inside its own
 * transaction (so the row is committed before SSE is pushed), then pushes to any active
 * SSE emitter. SSE emission is best-effort: absent subscribers are silently tolerated.
 */
@Component
public class InAppFallbackAdapter {

    private static final Logger log = LoggerFactory.getLogger(InAppFallbackAdapter.class);

    private final InAppNotificationRepository repository;
    private final SseEmitterRegistry sseRegistry;

    public InAppFallbackAdapter(InAppNotificationRepository repository,
                                SseEmitterRegistry sseRegistry) {
        this.repository  = repository;
        this.sseRegistry = sseRegistry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID persist(NotificationRequest request) {
        UUID id = UuidV7.generate();
        String severity = request.severity() != null ? request.severity() : "INFO";
        InAppNotificationEntity entity = InAppNotificationEntity.of(
                id,
                request.recipientUserId(),
                request.eventId(),
                request.category() != null ? request.category() : "GENERAL",
                request.subject() != null ? request.subject() : "(no subject)",
                request.body()    != null ? request.body()    : "",
                severity
        );
        repository.save(entity);
        log.info("in_app_notification_persisted id={} eventId={} channel={}",
                id, request.eventId(), request.channel());
        return id;
    }

    public void pushSse(UUID recipientUserId, UUID notificationId, NotificationRequest request) {
        var payload = new InAppPayload(
                notificationId,
                request.eventId(),
                request.category(),
                request.subject(),
                request.severity()
        );
        sseRegistry.push(recipientUserId, payload);
    }

    public record InAppPayload(
            UUID notificationId,
            UUID eventId,
            String category,
            String title,
            String severity
    ) {}
}
