package com.fieldservice.geo.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer meters for the geo travel-time adapter.
 *
 * <p>Published meters:
 * <ul>
 *   <li>{@code geo.travel.call.duration} — timer for provider HTTP calls, tagged by provider and outcome</li>
 *   <li>{@code geo.travel.call.errors} — counter for failed provider calls</li>
 *   <li>{@code geo.travel.degraded} — counter incremented on every Haversine fallback</li>
 *   <li>{@code geo.travel.cache.hit} — counter for Redis cache hits</li>
 * </ul>
 */
@Component
class GeoMetrics {

    static final String CALL_DURATION = "geo.travel.call.duration";
    static final String CALL_ERRORS = "geo.travel.call.errors";
    static final String DEGRADED = "geo.travel.degraded";
    static final String CACHE_HIT = "geo.travel.cache.hit";

    private final MeterRegistry registry;

    GeoMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordCall(String provider, String outcome, Duration duration) {
        Timer.builder(CALL_DURATION)
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(registry)
                .record(duration);
    }

    void recordError(String provider, String reason) {
        Counter.builder(CALL_ERRORS)
                .tag("provider", provider)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    void recordDegraded(String provider, int count) {
        Counter.builder(DEGRADED)
                .tag("provider", provider)
                .register(registry)
                .increment(count);
    }

    void recordCacheHit(String provider) {
        Counter.builder(CACHE_HIT)
                .tag("provider", provider)
                .register(registry)
                .increment();
    }
}
