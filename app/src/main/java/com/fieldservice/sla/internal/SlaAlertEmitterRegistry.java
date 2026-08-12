package com.fieldservice.sla.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe registry of active SLA alert SSE emitters, keyed by user ID.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register / remove emitters, enforcing per-user concurrent stream cap.</li>
 *   <li>Fan-out events to all eligible subscribers, removing failed emitters.</li>
 *   <li>Emit periodic heartbeats to keep connections alive through intermediaries.</li>
 *   <li>Deduplicate events by event id to prevent double delivery on reconnect.</li>
 *   <li>Expose Micrometer meters: active-streams gauge, events-emitted, failures, heartbeats.</li>
 * </ul>
 *
 * <p>Emitter lifecycle: callers register before returning the SseEmitter to Spring MVC;
 * completion/timeout/error callbacks remove the emitter from the registry.
 */
@Component
public class SlaAlertEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SlaAlertEmitterRegistry.class);

    // SSE event name constants
    public static final String EVENT_SLA_AT_RISK  = "SLA_AT_RISK";
    public static final String EVENT_SLA_BREACHED = "SLA_BREACHED";
    public static final String EVENT_HEARTBEAT    = "heartbeat";
    public static final String EVENT_RESYNC       = "resync";

    private record RegisteredEmitter(String userId, SseEmitter emitter) {}

    /** user id → list of active emitters for that user */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> byUser =
            new ConcurrentHashMap<>();

    /** recent event ids seen, for deduplication (bounded set, not an LRU for simplicity) */
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_SEEN_IDS = 2000;

    private final AtomicInteger totalEmitterCount = new AtomicInteger(0);
    private final SlaAlertStreamProperties props;
    private final MeterRegistry meterRegistry;
    private final Counter eventsAtRisk;
    private final Counter eventsBreached;
    private final Counter emitterFailures;
    private final Counter heartbeatCounter;
    private final Counter ticketRejections;

    private ScheduledExecutorService heartbeatScheduler;

    public SlaAlertEmitterRegistry(SlaAlertStreamProperties props, MeterRegistry meterRegistry) {
        this.props         = props;
        this.meterRegistry = meterRegistry;
        this.eventsAtRisk  = Counter.builder("sse_events_emitted_total")
                .tag("event_type", EVENT_SLA_AT_RISK)
                .description("SSE SLA_AT_RISK events emitted")
                .register(meterRegistry);
        this.eventsBreached = Counter.builder("sse_events_emitted_total")
                .tag("event_type", EVENT_SLA_BREACHED)
                .description("SSE SLA_BREACHED events emitted")
                .register(meterRegistry);
        this.emitterFailures = Counter.builder("sse_emitter_failures_total")
                .description("SSE emitter send failures")
                .register(meterRegistry);
        this.heartbeatCounter = Counter.builder("sse_heartbeats_total")
                .description("SSE heartbeat frames emitted")
                .register(meterRegistry);
        this.ticketRejections = Counter.builder("sse_ticket_rejections_total")
                .tag("reason", "stream_cap_exceeded")
                .description("SSE ticket rejections due to stream cap")
                .register(meterRegistry);
        Gauge.builder("sse_active_streams", totalEmitterCount, AtomicInteger::get)
                .description("Number of active SLA alert SSE streams")
                .register(meterRegistry);
    }

    @PostConstruct
    void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("sla-sse-heartbeat");
            return t;
        });
        int interval = props.getHeartbeatIntervalSeconds();
        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeatToAll, interval, interval, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
    }

    /**
     * Attempts to register an emitter for the given user.
     *
     * @return true if registered successfully; false if per-user cap exceeded (caller returns 429)
     */
    public boolean register(String userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> userEmitters = byUser.computeIfAbsent(
                userId, k -> new CopyOnWriteArrayList<>());

        if (userEmitters.size() >= props.getMaxConcurrentPerUser()) {
            ticketRejections.increment();
            return false;
        }

        userEmitters.add(emitter);
        totalEmitterCount.incrementAndGet();

        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        log.debug("sla.stream.registered userId={} total={}", userId, totalEmitterCount.get());
        return true;
    }

    /**
     * Fans out an event to all eligible subscribers.
     *
     * @param eventId   stable event identifier (from outbox event_id)
     * @param eventType SSE event name (SLA_AT_RISK or SLA_BREACHED)
     * @param dataJson  JSON payload string
     * @return number of emitters successfully sent to
     */
    public int fanOut(String eventId, String eventType, String dataJson) {
        // Deduplicate
        if (seenEventIds.size() > MAX_SEEN_IDS) {
            seenEventIds.clear();
        }
        if (!seenEventIds.add(eventId)) {
            log.debug("sla.stream.fanout.deduplicated eventId={}", eventId);
            return 0;
        }

        int successCount = 0;
        for (CopyOnWriteArrayList<SseEmitter> userEmitters : byUser.values()) {
            for (SseEmitter emitter : userEmitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(eventId)
                            .name(eventType)
                            .data(dataJson));
                    successCount++;
                    counterFor(eventType).increment();
                } catch (Exception e) {
                    log.debug("sla.stream.emitter_send_failure eventId={} traceId={}",
                            eventId, MDC.get("traceId"));
                    emitterFailures.increment();
                    // Remove the failed emitter — it is broken
                    String userId = findUserId(emitter);
                    if (userId != null) remove(userId, emitter);
                }
            }
        }

        return successCount;
    }

    /**
     * Returns true when there is at least one active emitter across all users.
     */
    public boolean hasActiveSubscribers() {
        return totalEmitterCount.get() > 0;
    }

    /**
     * Sends a resync signal on a specific emitter (for Last-Event-ID out-of-horizon).
     */
    public void sendResync(SseEmitter emitter, String reason) {
        try {
            emitter.send(SseEmitter.event()
                    .name(EVENT_RESYNC)
                    .data("{\"reason\":\"" + reason.replace("\"", "'") + "\"}"));
        } catch (Exception e) {
            log.debug("sla.stream.resync_send_failure");
        }
    }

    private void sendHeartbeatToAll() {
        String ts = Instant.now().toString();
        String data = "{\"serverTime\":\"" + ts + "\"}";
        List<SseEmitter> failed = new ArrayList<>();

        for (var entry : byUser.entrySet()) {
            String userId = entry.getKey();
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(EVENT_HEARTBEAT)
                            .comment("heartbeat")
                            .data(data));
                    heartbeatCounter.increment();
                } catch (Exception e) {
                    log.debug("sla.stream.heartbeat_failure userId={}", userId);
                    emitterFailures.increment();
                    failed.add(emitter);
                }
            }
            // Clean up failed emitters after iteration to avoid ConcurrentModification
            for (SseEmitter fe : failed) {
                remove(userId, fe);
            }
            failed.clear();
        }
    }

    private void remove(String userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = byUser.get(userId);
        if (list != null && list.remove(emitter)) {
            totalEmitterCount.decrementAndGet();
            if (list.isEmpty()) {
                byUser.remove(userId, list);
            }
        }
    }

    private String findUserId(SseEmitter emitter) {
        for (var entry : byUser.entrySet()) {
            if (entry.getValue().contains(emitter)) return entry.getKey();
        }
        return null;
    }

    private Counter counterFor(String eventType) {
        return EVENT_SLA_AT_RISK.equals(eventType) ? eventsAtRisk : eventsBreached;
    }
}
