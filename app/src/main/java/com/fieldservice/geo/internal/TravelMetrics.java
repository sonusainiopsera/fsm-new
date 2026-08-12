package com.fieldservice.geo.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer meters for the geo travel-time adapter.
 *
 * <p>Meters registered (all tagged by {@code provider}):
 * <ul>
 *   <li>{@code geo.travel.call.duration} — Timer, per-call latency</li>
 *   <li>{@code geo.travel.call.errors}   — Counter, incremented on any non-success</li>
 *   <li>{@code geo.travel.degraded}      — Counter, incremented when fallback is used</li>
 *   <li>{@code geo.travel.cache.hit}     — Counter, incremented on Redis hit</li>
 * </ul>
 */
class TravelMetrics {

    static final String PROVIDER_TAG = "provider";

    private final MeterRegistry registry;

    TravelMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void recordCallDuration(String provider, long latencyMs) {
        Timer.builder("geo.travel.call.duration")
                .tag(PROVIDER_TAG, provider)
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    void recordCallError(String provider, String reason) {
        Counter.builder("geo.travel.call.errors")
                .tag(PROVIDER_TAG, provider)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    void recordDegraded(String provider) {
        Counter.builder("geo.travel.degraded")
                .tag(PROVIDER_TAG, provider)
                .register(registry)
                .increment();
    }

    void recordCacheHit(String provider) {
        Counter.builder("geo.travel.cache.hit")
                .tag(PROVIDER_TAG, provider)
                .register(registry)
                .increment();
    }
}
