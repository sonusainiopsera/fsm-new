package com.fieldservice.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process registry of active SSE emitters keyed by user ID.
 *
 * <p>Push is best-effort: if the recipient has no active SSE connection (different JVM
 * in clustered deployment, session expired, etc.) the push is silently dropped.
 * The {@link InAppFallbackAdapter} persists the row first so the alert survives
 * a missing subscriber.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final ConcurrentHashMap<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID userId) {
        SseEmitter emitter = new SseEmitter(0L);
        SseEmitter previous = emitters.put(userId, emitter);
        if (previous != null) {
            previous.complete();
        }
        emitter.onCompletion(() -> emitters.remove(userId, emitter));
        emitter.onTimeout(()   -> emitters.remove(userId, emitter));
        emitter.onError(ex     -> emitters.remove(userId, emitter));
        return emitter;
    }

    public void push(UUID userId, Object payload) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("notification").data(payload));
        } catch (IOException e) {
            log.debug("sse_push_failed userId={} reason={}", userId, e.getMessage());
            emitters.remove(userId, emitter);
        }
    }

    public int activeCount() {
        return emitters.size();
    }
}
