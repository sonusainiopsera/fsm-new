package com.fieldservice.analytics.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer instrumentation for the analytics module (WO-161).
 *
 * <p>Meters exported to Prometheus actuator endpoint ({@code /actuator/prometheus}):
 * <ul>
 *   <li>{@code kpi_projection_staleness_seconds} — gauge per metric key: seconds since data_as_of</li>
 *   <li>{@code kpi_refresh_lag_seconds} — timer per metric key: wall-clock duration of a recompute</li>
 *   <li>{@code kpi_cache_hit_ratio} — counter-based ratio: hits / (hits + misses) per metric</li>
 * </ul>
 */
@Component
class AnalyticsMetrics {

    private final MeterRegistry registry;
    private final Clock clock;

    private final ConcurrentHashMap<String, AtomicLong> stalenessMillis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheHits   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> cacheMisses = new ConcurrentHashMap<>();

    AnalyticsMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /** Records the data_as_of timestamp for a metric; the staleness gauge is derived from it. */
    void recordDataAsOf(String metricKey, Instant dataAsOf) {
        stalenessMillis.computeIfAbsent(metricKey, k -> {
            AtomicLong holder = new AtomicLong(0L);
            Gauge.builder("kpi_projection_staleness_seconds", holder,
                            v -> (clock.millis() - v.get()) / 1000.0)
                    .tag("metric", k)
                    .description("Seconds since KPI projection was last refreshed")
                    .register(registry);
            return holder;
        }).set(dataAsOf.toEpochMilli());
    }

    /** Returns a Timer for measuring the wall-clock duration of a projection refresh. */
    Timer refreshLagTimer(String metricKey) {
        return Timer.builder("kpi_refresh_lag_seconds")
                .tag("metric", metricKey)
                .description("Wall-clock duration of a KPI projection recomputation")
                .register(registry);
    }

    void recordCacheHit(String metricKey) {
        cacheHits.computeIfAbsent(metricKey, k ->
                Counter.builder("kpi_cache_hits_total")
                        .tag("metric", k)
                        .register(registry)).increment();
    }

    void recordCacheMiss(String metricKey) {
        cacheMisses.computeIfAbsent(metricKey, k ->
                Counter.builder("kpi_cache_misses_total")
                        .tag("metric", k)
                        .register(registry)).increment();
    }

    /** Returns the cache hit ratio for a metric, or NaN if no data yet. */
    double cacheHitRatio(String metricKey) {
        Counter hits   = cacheHits.get(metricKey);
        Counter misses = cacheMisses.get(metricKey);
        if (hits == null && misses == null) return Double.NaN;
        double h = hits   != null ? hits.count()   : 0.0;
        double m = misses != null ? misses.count() : 0.0;
        double total = h + m;
        return total == 0 ? Double.NaN : h / total;
    }
}
