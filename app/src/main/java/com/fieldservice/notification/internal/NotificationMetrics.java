package com.fieldservice.notification.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer metric registration for the notification module.
 *
 * <p>Metrics:
 * <ul>
 *   <li>{@code notification_send_total} — counter tagged by channel, adapter, outcome</li>
 *   <li>{@code notification_send_duration_seconds} — timer tagged by channel, adapter, outcome</li>
 *   <li>{@code notification_breaker_state} — gauge (0=closed, 1=open, 2=half-open)</li>
 * </ul>
 */
@Component
class NotificationMetrics {

    static final String SEND_TOTAL = "notification_send_total";
    static final String SEND_DURATION = "notification_send_duration_seconds";
    static final String BREAKER_STATE = "notification_breaker_state";

    private final MeterRegistry registry;

    NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordSend(String channel, String adapter, String outcome, Duration duration) {
        Counter.builder(SEND_TOTAL)
                .tag("channel", channel)
                .tag("adapter", adapter)
                .tag("outcome", outcome)
                .register(registry)
                .increment();

        Timer.builder(SEND_DURATION)
                .tag("channel", channel)
                .tag("adapter", adapter)
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    void bindBreakerGauge(CircuitBreaker cb) {
        registry.gauge(BREAKER_STATE, cb, breaker -> switch (breaker.getState()) {
            case CLOSED       -> 0.0;
            case OPEN         -> 1.0;
            case HALF_OPEN    -> 2.0;
            default           -> -1.0;
        });
    }
}
