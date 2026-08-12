package com.fieldservice.sla.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe registry for active SSE emitters, keyed by subscriber user ID.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register and remove emitters; enforce per-user and global concurrency caps.</li>
 *   <li>Send alert events to all registered emitters, removing on I/O failure.</li>
 *   <li>Emit heartbeat comment frames on a configured interval to keep intermediaries alive.</li>
 *   <li>Deduplicate by event ID so a reconnected emitter never sees a duplicate delivery.</li>
 * </ul>
 *
 * <p>Emitters use a {@code 0L} timeout (no server-side timeout); clients reconnect when
 * heartbeats stop arriving (i.e. when the connection is genuinely dead).
 */
@Component
public class SlaAlertEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SlaAlertEmitterRegistry.class);

    private final int maxPerUser;
    private final int maxGlobal;
    private final long heartbeatIntervalSeconds;

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<EmitterEntry>> byUser =
            new ConcurrentHashMap<>();
    private final AtomicInteger totalCount = new AtomicInteger(0);

    private final Counter heartbeatCounter;
    private final Counter failureCounter;

    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sla-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public SlaAlertEmitterRegistry(
            @Value("${sla.stream.max-per-user:3}") int maxPerUser,
            @Value("${sla.stream.max-global:200}") int maxGlobal,
            @Value("${sla.stream.heartbeat-interval-seconds:15}") long heartbeatIntervalSeconds,
            MeterRegistry meterRegistry) {
        this.maxPerUser = maxPerUser;
        this.maxGlobal  = maxGlobal;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;

        Gauge.builder("sse_active_streams", totalCount, AtomicInteger::get)
                .description("Number of active SSE SLA alert streams")
                .register(meterRegistry);

        this.heartbeatCounter = Counter.builder("sse_heartbeats_total")
                .description("Total SSE heartbeat frames emitted")
                .register(meterRegistry);

        this.failureCounter = Counter.builder("sse_emitter_failures_total")
                .description("Total SSE emitter send failures")
                .register(meterRegistry);

        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * Registers a new emitter for the given user.
     *
     * @throws StreamCapExceededException when the per-user or global cap is reached
     */
    public SseEmitter register(UUID userId) {
        CopyOnWriteArrayList<EmitterEntry> userEmitters =
                byUser.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>());

        if (userEmitters.size() >= maxPerUser || totalCount.get() >= maxGlobal) {
            throw new StreamCapExceededException(heartbeatIntervalSeconds);
        }

        SseEmitter emitter = new SseEmitter(0L);
        UUID emitterId = UUID.randomUUID();
        EmitterEntry entry = new EmitterEntry(emitterId, userId, emitter);

        userEmitters.add(entry);
        totalCount.incrementAndGet();
        log.debug("sse_emitter_registered user_id={} emitter_id={} total={}", userId, emitterId, totalCount.get());

        emitter.onCompletion(() -> remove(userId, emitterId));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(userId, emitterId);
        });
        emitter.onError(e -> remove(userId, emitterId));

        return emitter;
    }

    /**
     * Sends an alert event to all registered emitters.
     * Returns {@code true} if at least one emitter received the event successfully.
     */
    public boolean sendToAll(String eventId, String eventType, String jsonData,
                             MeterRegistry meterRegistry) {
        boolean anySuccess = false;
        for (CopyOnWriteArrayList<EmitterEntry> userEmitters : byUser.values()) {
            for (EmitterEntry entry : userEmitters) {
                if (entry.hasReceived(eventId)) {
                    continue;
                }
                try {
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .id(eventId)
                            .name(eventType)
                            .data(jsonData);
                    entry.emitter().send(event);
                    entry.markReceived(eventId);
                    anySuccess = true;
                    Counter.builder("sse_events_emitted_total")
                            .tag("event_type", eventType)
                            .description("Total SSE SLA alert events emitted by type")
                            .register(meterRegistry)
                            .increment();
                } catch (IOException e) {
                    log.info("sse_emitter_send_failure user_id={} emitter_id={}", entry.userId(), entry.emitterId());
                    failureCounter.increment();
                    remove(entry.userId(), entry.emitterId());
                }
            }
        }
        return anySuccess;
    }

    /**
     * Sends a replay entry to a specific emitter (for Last-Event-ID resume on connect).
     */
    public void sendReplay(SseEmitter emitter, List<SlaAlertReplayBuffer.ReplayEntry> entries) {
        for (SlaAlertReplayBuffer.ReplayEntry entry : entries) {
            try {
                emitter.send(SseEmitter.event()
                        .id(entry.eventId())
                        .name(entry.eventType())
                        .data(entry.jsonData()));
            } catch (IOException e) {
                log.debug("sse_replay_send_failure: {}", e.getMessage());
                return;
            }
        }
    }

    /**
     * Sends a resync signal telling the client to refetch current state.
     */
    public void sendResync(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event()
                    .name("RESYNC_NEEDED")
                    .data("{}"));
        } catch (IOException e) {
            log.debug("sse_resync_send_failure: {}", e.getMessage());
        }
    }

    public int countForUser(UUID userId) {
        CopyOnWriteArrayList<EmitterEntry> list = byUser.get(userId);
        return list == null ? 0 : list.size();
    }

    private void remove(UUID userId, UUID emitterId) {
        CopyOnWriteArrayList<EmitterEntry> userEmitters = byUser.get(userId);
        if (userEmitters != null) {
            boolean removed = userEmitters.removeIf(e -> e.emitterId().equals(emitterId));
            if (removed) {
                totalCount.decrementAndGet();
                log.debug("sse_emitter_removed user_id={} emitter_id={} total={}",
                        userId, emitterId, totalCount.get());
            }
            if (userEmitters.isEmpty()) {
                byUser.remove(userId, userEmitters);
            }
        }
    }

    private void sendHeartbeats() {
        String timestamp = Instant.now().toString();
        List<EmitterEntry> toRemove = new ArrayList<>();

        for (CopyOnWriteArrayList<EmitterEntry> userEmitters : byUser.values()) {
            for (EmitterEntry entry : userEmitters) {
                try {
                    entry.emitter().send(SseEmitter.event().comment("heartbeat " + timestamp));
                    heartbeatCounter.increment();
                } catch (IOException e) {
                    toRemove.add(entry);
                }
            }
        }

        for (EmitterEntry entry : toRemove) {
            remove(entry.userId(), entry.emitterId());
        }
    }

    static class EmitterEntry {
        private final UUID emitterId;
        private final UUID userId;
        private final SseEmitter emitter;
        private final ConcurrentHashMap<String, Boolean> receivedIds = new ConcurrentHashMap<>();

        EmitterEntry(UUID emitterId, UUID userId, SseEmitter emitter) {
            this.emitterId = emitterId;
            this.userId    = userId;
            this.emitter   = emitter;
        }

        UUID emitterId()           { return emitterId; }
        UUID userId()              { return userId; }
        SseEmitter emitter()       { return emitter; }
        boolean hasReceived(String id) { return receivedIds.containsKey(id); }
        void markReceived(String id)   { receivedIds.put(id, Boolean.TRUE); }
    }

    /**
     * Thrown when the per-user or global concurrent stream cap is exceeded.
     * Mapped to 429 with Retry-After.
     */
    public static class StreamCapExceededException extends RuntimeException {
        private final long retryAfterSeconds;

        StreamCapExceededException(long retryAfterSeconds) {
            super("Stream cap exceeded", null, true, false);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }
}
