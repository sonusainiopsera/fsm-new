package com.fieldservice.analytics.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Micrometer instrumentation for the analytics substrate.
 *
 * <p>Exported metrics:
 * <ul>
 *   <li>{@code kpi_projection_staleness_seconds} — Gauge: age of the oldest projection
 *       per {@code metric_key} tag. Consumed by the Prometheus/Ops dashboard.</li>
 *   <li>{@code kpi_refresh_lag_seconds} — Gauge: wall-clock time since the last
 *       successful refresh per metric.</li>
 *   <li>{@code kpi_cache_hit_ratio} — Gauge: rolling hit/(hit+miss) per metric.</li>
 *   <li>{@code kpi_refresh_total} — Counter: total successful refreshes per metric.</li>
 *   <li>{@code kpi_refresh_errors_total} — Counter: refresh failures per metric.</li>
 * </ul>
 */
@Component
class AnalyticsMetrics {

    private final MeterRegistry meterRegistry;
    private final Clock         clock;

    private final ConcurrentHashMap<String, AtomicLong> stalenessByMetric  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> refreshLagByMetric = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder>  cacheHitsByMetric  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder>  cacheMissByMetric  = new ConcurrentHashMap<>();

    AnalyticsMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock         = clock;
    }

    void recordStaleness(String metricKey, long stalenessSeconds) {
        stalenessByMetric
                .computeIfAbsent(metricKey, k -> {
                    AtomicLong holder = new AtomicLong(stalenessSeconds);
                    Gauge.builder("kpi_projection_staleness_seconds", holder, AtomicLong::get)
                            .tag("metric", k)
                            .description("Age of the KPI projection in seconds")
                            .register(meterRegistry);
                    return holder;
                })
                .set(stalenessSeconds);
    }

    void recordRefreshLag(String metricKey, long lagSeconds) {
        refreshLagByMetric
                .computeIfAbsent(metricKey, k -> {
                    AtomicLong holder = new AtomicLong(lagSeconds);
                    Gauge.builder("kpi_refresh_lag_seconds", holder, AtomicLong::get)
                            .tag("metric", k)
                            .description("Seconds since last successful KPI refresh")
                            .register(meterRegistry);
                    return holder;
                })
                .set(lagSeconds);
    }

    void recordCacheHit(String metricKey) {
        ensureCacheGauge(metricKey);
        cacheHitsByMetric.computeIfAbsent(metricKey, k -> new LongAdder()).increment();
    }

    void recordCacheMiss(String metricKey) {
        ensureCacheGauge(metricKey);
        cacheMissByMetric.computeIfAbsent(metricKey, k -> new LongAdder()).increment();
    }

    private void ensureCacheGauge(String metricKey) {
        cacheHitsByMetric.computeIfAbsent(metricKey, k -> {
            LongAdder hits   = new LongAdder();
            LongAdder misses = cacheMissByMetric.computeIfAbsent(k, x -> new LongAdder());
            Gauge.builder("kpi_cache_hit_ratio", hits, h -> {
                     long total = h.longValue() + misses.longValue();
                     return total == 0 ? 1.0 : (double) h.longValue() / total;
                 })
                 .tag("metric", k)
                 .description("Rolling KPI cache hit ratio")
                 .register(meterRegistry);
            return hits;
        });
    }

    void recordRefreshSuccess(String metricKey) {
        Counter.builder("kpi_refresh_total")
               .tag("metric", metricKey)
               .description("Total successful KPI projection refreshes")
               .register(meterRegistry)
               .increment();
    }

    void recordRefreshError(String metricKey) {
        Counter.builder("kpi_refresh_errors_total")
               .tag("metric", metricKey)
               .description("Total failed KPI projection refresh attempts")
               .register(meterRegistry)
               .increment();
    }

    void updateStalenessBulk(List<KpiProjectionEntity> projections) {
        long nowEpoch = clock.instant().getEpochSecond();
        for (KpiProjectionEntity e : projections) {
            long staleness = nowEpoch - e.getDataAsOf().getEpochSecond();
            recordStaleness(e.getMetricKey(), Math.max(0L, staleness));
        }
    }

}
