package com.fieldservice.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of active SSE connections keyed by user ID.
 *
 * <p>The registry is a shared in-memory map. When the in-app fallback adapter stores a
 * durable notification row, it calls {@link #tryPush} to deliver a real-time hint to any
 * connected subscriber. Push failure or an absent subscriber is silently tolerated — the
 * persisted row is the authoritative delivery guarantee.
 *
 * <p>This bean is intentionally not restricted to the {@code worker} profile; the api tier
 * registers subscribers via {@link AlertSseController}, and the worker tier (in the same JVM)
 * calls {@link #tryPush} via {@link InAppFallbackAdapter}.
 */
@Component
public class AlertSseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(AlertSseEmitterRegistry.class);

    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * Registers a new SSE emitter for the given user, replacing any previous emitter.
     * The emitter is automatically removed on completion, timeout, or error.
     */
    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.put(userId, emitter);

        Runnable removeOnDone = () -> emitters.remove(userId, emitter);
        emitter.onCompletion(removeOnDone);
        emitter.onTimeout(removeOnDone);
        emitter.onError(ex -> {
            log.debug("sse_emitter error userId={} error={}", userId, ex.getMessage());
            emitters.remove(userId, emitter);
        });

        log.debug("sse_emitter registered userId={} total={}", userId, emitters.size());
        return emitter;
    }

    /**
     * Attempts to push an event to the subscriber for the given user ID.
     * Silently succeeds if there is no active subscriber.
     */
    void tryPush(UUID userId, InAppNotificationEntity notification) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .id(notification.getId().toString())
                    .name("in_app_notification")
                    .data(new SsePayload(
                            notification.getId(),
                            notification.getCategory(),
                            notification.getTitle(),
                            notification.getBody(),
                            notification.getSeverity(),
                            notification.getCreatedAt() != null ? notification.getCreatedAt().toEpochMilli() : 0L
                    )));
        } catch (IOException e) {
            log.debug("sse_push_failed userId={} error={}", userId, e.getMessage());
            emitters.remove(userId, emitter);
        }
    }

    int subscriberCount() {
        return emitters.size();
    }

    record SsePayload(UUID id, String category, String title, String body, String severity, long createdAtMs) {}
}
