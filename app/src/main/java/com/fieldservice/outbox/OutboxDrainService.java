package com.fieldservice.outbox;

import com.fieldservice.platform.outbox.EventHandler;
import com.fieldservice.platform.outbox.EventHandlerContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Core outbox drain logic.
 *
 * <p>Each {@link #drainBatch} call loops up to {@code batchSize} times; each iteration
 * claims exactly one eligible event in its own {@code REQUIRES_NEW} transaction using
 * {@code FOR UPDATE SKIP LOCKED}, dispatches to the matching {@link EventHandler}, and
 * records success or failure atomically. A crash mid-dispatch releases the row lock and
 * the event is re-claimed on the next pass.
 */
@Component
public class OutboxDrainService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainService.class);

    private static final String CLAIM_SQL = """
            SELECT event_id, event_type, aggregate_type, aggregate_id,
                   payload::text AS payload, trace_id, actor_user_id,
                   created_at, attempt_count
            FROM outbox_event
            WHERE published_at IS NULL
              AND dead_lettered_at IS NULL
              AND next_attempt_at <= now()
            ORDER BY created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, List<EventHandler>> handlersByType;
    private final OutboxPollerProperties properties;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNew;
    private final ScheduledExecutorService timeoutScheduler;

    public OutboxDrainService(JdbcTemplate jdbcTemplate,
                              List<EventHandler> handlers,
                              OutboxPollerProperties properties,
                              MeterRegistry meterRegistry,
                              PlatformTransactionManager txManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.handlersByType = handlers.stream()
                .collect(Collectors.groupingBy(EventHandler::getSupportedEventType));
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-dispatch-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    void registerMetrics() {
        AtomicLong queueDepthHolder = new AtomicLong(0);
        AtomicLong oldestAgeHolder = new AtomicLong(0);
        AtomicLong deadLetterHolder = new AtomicLong(0);

        io.micrometer.core.instrument.Gauge.builder("outbox.queue.depth",
                        queueDepthHolder, h -> refreshQueueDepth(h))
                .description("Number of unpublished, non-dead-lettered outbox events")
                .register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("outbox.oldest.event.age.seconds",
                        oldestAgeHolder, h -> refreshOldestAge(h))
                .description("Age in seconds of the oldest unclaimed outbox event")
                .register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder("outbox.dead.letter.count",
                        deadLetterHolder, h -> refreshDeadLetterCount(h))
                .description("Number of dead-lettered outbox events")
                .register(meterRegistry);
    }

    /**
     * Drains up to {@code batchSize} events from the outbox.
     *
     * @return number of events processed (success or recorded failure) in this pass;
     *         {@code 0} means the queue was empty
     */
    public int drainBatch(int batchSize) {
        int processed = 0;
        for (int i = 0; i < batchSize; i++) {
            Boolean claimed = requiresNew.execute(status -> processOneEvent());
            if (!Boolean.TRUE.equals(claimed)) {
                break;
            }
            processed++;
        }
        return processed;
    }

    // -----------------------------------------------------------------------
    // Internal: single-event claim + dispatch (runs inside REQUIRES_NEW TX)
    // -----------------------------------------------------------------------

    private boolean processOneEvent() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(CLAIM_SQL);
        if (rows.isEmpty()) {
            return false;
        }
        Map<String, Object> row = rows.get(0);

        UUID eventId = (UUID) row.get("event_id");
        String eventType = (String) row.get("event_type");
        String traceId = (String) row.get("trace_id");
        int attemptCount = ((Number) row.get("attempt_count")).intValue();

        EventHandlerContext ctx = new EventHandlerContext(
                eventId,
                eventType,
                (String) row.get("aggregate_type"),
                (UUID) row.get("aggregate_id"),
                (String) row.get("payload"),
                traceId,
                (UUID) row.get("actor_user_id"),
                attemptCount + 1
        );

        String prevTraceId = MDC.get("traceId");
        try {
            MDC.put("traceId", traceId != null ? traceId : "");
            dispatchWithTimeout(ctx);
            markPublished(eventId);
            recordSuccess(eventType);
        } catch (Exception e) {
            recordFailure(eventId, eventType, attemptCount + 1, e);
        } finally {
            if (prevTraceId != null) {
                MDC.put("traceId", prevTraceId);
            } else {
                MDC.remove("traceId");
            }
        }
        return true;
    }

    private void dispatchWithTimeout(EventHandlerContext ctx) throws Exception {
        List<EventHandler> handlers = handlersByType.get(ctx.eventType());
        if (handlers == null || handlers.isEmpty()) {
            log.warn("eventId={} eventType={} traceId={} — no registered handler; marking published",
                    ctx.eventId(), ctx.eventType(), ctx.traceId());
            meterRegistry.counter("outbox.unhandled.event.type",
                    "event_type", ctx.eventType()).increment();
            return;
        }

        Thread currentThread = Thread.currentThread();
        ScheduledFuture<?> interruptFuture = timeoutScheduler.schedule(
                currentThread::interrupt,
                properties.getDispatchTimeoutMs(), TimeUnit.MILLISECONDS);

        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "failure";
        try {
            for (EventHandler handler : handlers) {
                log.debug("eventId={} eventType={} attempt={} consumer={} traceId={} — dispatching",
                        ctx.eventId(), ctx.eventType(), ctx.attemptNumber(),
                        handler.getClass().getSimpleName(), ctx.traceId());
                handler.handle(ctx);
            }
            result = "success";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Handler timed out after " + properties.getDispatchTimeoutMs() + "ms", e);
        } finally {
            interruptFuture.cancel(false);
            sample.stop(Timer.builder("outbox.dispatch.duration")
                    .tag("event_type", ctx.eventType())
                    .tag("result", result)
                    .register(meterRegistry));
        }
    }

    private void markPublished(UUID eventId) {
        jdbcTemplate.update(
                "UPDATE outbox_event SET published_at = now() WHERE event_id = ?",
                eventId);
    }

    private void recordSuccess(String eventType) {
        meterRegistry.counter("outbox.dispatch.success", "event_type", eventType).increment();
    }

    private void recordFailure(UUID eventId, String eventType, int newAttemptCount, Exception cause) {
        String lastError = cause.getClass().getName() + ": " + cause.getMessage();
        log.error("eventId={} eventType={} attempt={} — dispatch failed: {}",
                eventId, eventType, newAttemptCount, lastError, cause);

        meterRegistry.counter("outbox.dispatch.failure", "event_type", eventType).increment();

        if (newAttemptCount >= properties.getMaxAttempts()) {
            jdbcTemplate.update("""
                    UPDATE outbox_event
                    SET attempt_count = ?, last_error = ?, dead_lettered_at = now()
                    WHERE event_id = ?
                    """, newAttemptCount, lastError, eventId);
            meterRegistry.counter("outbox.dead.letter", "event_type", eventType).increment();
            log.warn("eventId={} eventType={} — dead-lettered after {} attempts", eventId, eventType, newAttemptCount);
        } else {
            Instant nextAttempt = BackoffCalculator.nextAttemptAt(
                    properties.getBackoffBaseMs(), properties.getBackoffCapMs(),
                    properties.getJitterFraction(), newAttemptCount, Instant.now());
            jdbcTemplate.update("""
                    UPDATE outbox_event
                    SET attempt_count = ?, last_error = ?, next_attempt_at = ?
                    WHERE event_id = ?
                    """, newAttemptCount, lastError, nextAttempt, eventId);
        }
    }

    // -----------------------------------------------------------------------
    // Gauge refresh helpers (called by Micrometer on scrape)
    // -----------------------------------------------------------------------

    private double refreshQueueDepth(AtomicLong holder) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL AND dead_lettered_at IS NULL",
                    Long.class);
            holder.set(count != null ? count : 0L);
        } catch (Exception ignored) {
        }
        return holder.get();
    }

    private double refreshOldestAge(AtomicLong holder) {
        try {
            Double ageSeconds = jdbcTemplate.queryForObject(
                    """
                    SELECT EXTRACT(EPOCH FROM (now() - MIN(created_at)))
                    FROM outbox_event
                    WHERE published_at IS NULL AND dead_lettered_at IS NULL
                    """, Double.class);
            holder.set(ageSeconds != null ? (long) ageSeconds.doubleValue() : 0L);
        } catch (Exception ignored) {
        }
        return holder.get();
    }

    private double refreshDeadLetterCount(AtomicLong holder) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE dead_lettered_at IS NOT NULL",
                    Long.class);
            holder.set(count != null ? count : 0L);
        } catch (Exception ignored) {
        }
        return holder.get();
    }
}
