package com.fieldservice.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer metrics for the outbox drain loop.
 *
 * <p>Registered metrics:
 * <ul>
 *   <li>{@code outbox.queue.depth} — gauge: unpublished, non-dead-lettered event count</li>
 *   <li>{@code outbox.queue.oldest.age.seconds} — gauge: age of the oldest pending event</li>
 *   <li>{@code outbox.dispatch.duration[eventType,result]} — timer: per-type dispatch latency</li>
 *   <li>{@code outbox.dispatch.success[eventType]} — counter: successful dispatches per type</li>
 *   <li>{@code outbox.dispatch.failure[eventType]} — counter: failed dispatches per type</li>
 *   <li>{@code outbox.dead.letter.count} — counter: events moved to dead-letter state</li>
 *   <li>{@code outbox.unhandled.type[eventType]} — counter: events with no registered handler</li>
 * </ul>
 */
@Component
class OutboxPollerMetrics {

    private static final String DEPTH_SQL =
            "SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL AND dead_lettered_at IS NULL";

    private static final String OLDEST_AGE_SQL =
            "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0) " +
            "FROM outbox_event WHERE published_at IS NULL AND dead_lettered_at IS NULL";

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;

    OutboxPollerMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;

        Gauge.builder("outbox.queue.depth", this, OutboxPollerMetrics::queryQueueDepth)
                .description("Number of unpublished, non-dead-lettered outbox events")
                .register(registry);

        Gauge.builder("outbox.queue.oldest.age.seconds", this, OutboxPollerMetrics::queryOldestAgeSeconds)
                .description("Age in seconds of the oldest pending outbox event; 0 when queue is empty")
                .register(registry);
    }

    void recordDispatchSuccess(String eventType, long durationNanos) {
        Timer.builder("outbox.dispatch.duration")
                .tag("eventType", eventType)
                .tag("result", "success")
                .description("Duration of outbox event handler dispatch")
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        Counter.builder("outbox.dispatch.success")
                .tag("eventType", eventType)
                .description("Successful outbox event dispatches")
                .register(registry)
                .increment();
    }

    void recordDispatchFailure(String eventType) {
        Counter.builder("outbox.dispatch.failure")
                .tag("eventType", eventType)
                .description("Failed outbox event dispatches")
                .register(registry)
                .increment();
    }

    void recordDeadLetter() {
        Counter.builder("outbox.dead.letter.count")
                .description("Outbox events moved to dead-letter state after max attempts")
                .register(registry)
                .increment();
    }

    void recordUnhandledType(String eventType) {
        Counter.builder("outbox.unhandled.type")
                .tag("eventType", eventType)
                .description("Outbox events with no registered handler; published with warning")
                .register(registry)
                .increment();
    }

    private double queryQueueDepth() {
        try {
            Long count = jdbc.queryForObject(DEPTH_SQL, Long.class);
            return count != null ? count : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double queryOldestAgeSeconds() {
        try {
            Double age = jdbc.queryForObject(OLDEST_AGE_SQL, Double.class);
            return age != null ? age : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
