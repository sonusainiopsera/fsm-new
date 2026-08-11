package com.fieldservice.analytics.internal;

import com.fieldservice.platform.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Drives KPI projection refresh for a single metric key.
 *
 * <p>Called by {@link AnalyticsWorker} after the debounce window expires.
 * Collects registered {@link KpiAggregator} implementations, calls
 * {@link KpiAggregator#compute()}, upserts results into {@code kpi_projection},
 * and populates the Redis cache.
 *
 * <p>If no aggregator is registered for a metric key, the refresh is a no-op —
 * the metric key is simply dequeued without writing any rows. This allows
 * WO-066 through WO-069 to add aggregators incrementally.
 */
@Service
class KpiProjectionRefreshService {

    private static final Logger log = LoggerFactory.getLogger(KpiProjectionRefreshService.class);

    private final Map<String, KpiAggregator> aggregators;
    private final KpiProjectionRepository   projectionRepository;
    private final KpiProjectionCache        cache;
    private final AnalyticsMetrics          metrics;
    private final Clock                     clock;

    KpiProjectionRefreshService(List<KpiAggregator>      aggregators,
                                KpiProjectionRepository   projectionRepository,
                                KpiProjectionCache        cache,
                                AnalyticsMetrics          metrics,
                                Clock                     clock) {
        this.aggregators           = aggregators.stream()
                .collect(Collectors.toMap(KpiAggregator::metricKey, Function.identity()));
        this.projectionRepository  = projectionRepository;
        this.cache                 = cache;
        this.metrics               = metrics;
        this.clock                 = clock;
    }

    /**
     * Refreshes the projection for {@code metricKey}.
     * Silently no-ops if no aggregator is registered (substrate phase — aggregators added later).
     */
    @Transactional
    void refresh(String metricKey) {
        KpiAggregator aggregator = aggregators.get(metricKey);
        if (aggregator == null) {
            log.debug("analytics_no_aggregator metric_key={}", metricKey);
            return;
        }

        long startMs = System.currentTimeMillis();
        try {
            List<KpiAggregator.KpiAggregatorResult> results = aggregator.compute();
            for (KpiAggregator.KpiAggregatorResult r : results) {
                upsertAndCache(metricKey, r);
            }
            long lagMs = System.currentTimeMillis() - startMs;
            metrics.recordRefreshSuccess(metricKey);
            metrics.recordRefreshLag(metricKey, lagMs / 1000L);
            log.debug("analytics_refresh_complete metric_key={} rows={} lag_ms={}",
                    metricKey, results.size(), lagMs);
        } catch (Exception e) {
            metrics.recordRefreshError(metricKey);
            log.error("analytics_refresh_failed metric_key={} error={}", metricKey, e.getMessage(), e);
            throw e;
        }
    }

    private void upsertAndCache(String metricKey, KpiAggregator.KpiAggregatorResult r) {
        projectionRepository.upsert(
                UuidV7.generate(),
                metricKey,
                r.segmentKey(),
                r.windowKey(),
                r.numerator(),
                r.denominator(),
                r.value(),
                r.sampleCount(),
                r.maturity(),
                r.dataAsOf(),
                false
        );

        Optional<KpiProjectionEntity> saved = projectionRepository.findByMetricKeyAndSegmentKeyAndWindowKey(
                metricKey, r.segmentKey(), r.windowKey());

        saved.ifPresent(entity -> {
            long staleness = staleness(entity.getDataAsOf());
            cache.put(entity.toDto(staleness));
            metrics.recordStaleness(metricKey, staleness);
        });
    }

    private long staleness(Instant dataAsOf) {
        long s = clock.instant().getEpochSecond() - dataAsOf.getEpochSecond();
        return Math.max(0L, s);
    }
}
