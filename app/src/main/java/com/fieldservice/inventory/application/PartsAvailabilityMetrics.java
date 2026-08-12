package com.fieldservice.inventory.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer meters for the parts availability lookup (WO-151 AC-10).
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code inventory.availability.cache.hits} — cache hits</li>
 *   <li>{@code inventory.availability.cache.misses} — cache misses</li>
 *   <li>{@code inventory.availability.degraded.total} — queries that fell through to DB on cache outage</li>
 *   <li>{@code inventory.availability.cache.hit_ratio} — gauge: hits / (hits + misses)</li>
 * </ul>
 */
@Component
public class PartsAvailabilityMetrics {

    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter degradedTotal;

    private final AtomicLong hitCount  = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);

    public PartsAvailabilityMetrics(MeterRegistry registry) {
        this.cacheHits = Counter.builder("inventory.availability.cache.hits")
                .description("Parts availability cache hits")
                .register(registry);

        this.cacheMisses = Counter.builder("inventory.availability.cache.misses")
                .description("Parts availability cache misses")
                .register(registry);

        this.degradedTotal = Counter.builder("inventory.availability.degraded.total")
                .description("Availability lookups that fell through to DB on cache outage")
                .register(registry);

        registry.gauge("inventory.availability.cache.hit_ratio",
                Tags.empty(),
                this,
                m -> {
                    long hits   = m.hitCount.get();
                    long misses = m.missCount.get();
                    long total  = hits + misses;
                    return total == 0 ? 0.0 : (double) hits / total;
                });
    }

    public void recordCacheHit() {
        cacheHits.increment();
        hitCount.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
        missCount.incrementAndGet();
    }

    public void recordDegraded() {
        degradedTotal.increment();
    }
}
