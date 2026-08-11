package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link KpiProjectionQuery} and manages projection upsert from aggregation results.
 *
 * <p>Package-private service. Callers access KPI projections only via
 * {@link KpiProjectionQuery}.
 *
 * <p>Read path (findProjection):
 * <ol>
 *   <li>Check Redis cache for a version-keyed entry (cache hit → return).</li>
 *   <li>Read from {@code kpi_projection} table.</li>
 *   <li>Populate Redis on miss.</li>
 *   <li>On Redis failure → DegradationPolicy.onCacheFailure (degraded but still returns value).</li>
 * </ol>
 *
 * <p>Write path (recomputeAndPersist):
 * <ol>
 *   <li>Run aggregation query against replica datasource.</li>
 *   <li>Upsert the projection row (bump projectionVersion).</li>
 *   <li>Evict and repopulate Redis cache.</li>
 *   <li>On replica failure → mark projection degraded and upsert with last known value.</li>
 * </ol>
 */
@Service
class KpiProjectionService implements KpiProjectionQuery {

    private static final Logger log = LoggerFactory.getLogger(KpiProjectionService.class);

    private final KpiProjectionRepository repository;
    private final KpiAggregationQueries aggregationQueries;
    private final DegradationPolicy degradationPolicy;
    private final AnalyticsMetrics metrics;
    private final Clock clock;

    @Nullable
    private final AnalyticsRedisCache redisCache;

    KpiProjectionService(
            KpiProjectionRepository repository,
            KpiAggregationQueries aggregationQueries,
            DegradationPolicy degradationPolicy,
            AnalyticsMetrics metrics,
            Clock clock,
            @Nullable @org.springframework.beans.factory.annotation.Autowired(required = false)
            AnalyticsRedisCache redisCache) {
        this.repository = repository;
        this.aggregationQueries = aggregationQueries;
        this.degradationPolicy = degradationPolicy;
        this.metrics = metrics;
        this.clock = clock;
        this.redisCache = redisCache;
    }

    // -------------------------------------------------------------------------
    // KpiProjectionQuery — public read port
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<KpiProjection> findProjection(String metricKey, String segmentKey, String windowKey) {
        Optional<KpiProjectionEntity> entityOpt =
                repository.findByMetricKeyAndSegmentKeyAndWindowKey(metricKey, segmentKey, windowKey);
        if (entityOpt.isEmpty()) return Optional.empty();

        KpiProjectionEntity entity = entityOpt.get();
        long version = entity.getProjectionVersion();

        // Try cache first
        if (redisCache != null) {
            try {
                KpiProjection cached = redisCache.get(metricKey, segmentKey, windowKey, version);
                if (cached != null) {
                    metrics.recordCacheHit(metricKey);
                    return Optional.of(cached);
                }
                metrics.recordCacheMiss(metricKey);
            } catch (AnalyticsRedisCache.AnalyticsCacheException ex) {
                metrics.recordCacheMiss(metricKey);
                KpiProjection degraded = degradationPolicy.onCacheFailure(
                        metricKey, segmentKey, windowKey, entity, ex);
                return Optional.of(degraded);
            }
        }

        KpiProjection projection = toProjection(entity);
        if (redisCache != null) {
            redisCache.put(projection);
        }
        metrics.recordDataAsOf(metricKey, entity.getDataAsOf());
        return Optional.of(projection);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KpiProjection> findAllByMetricKey(String metricKey) {
        return repository.findAllByMetricKey(metricKey).stream()
                .map(this::toProjection)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<KpiProjection> findDegraded() {
        return repository.findAllDegraded().stream()
                .map(this::toProjection)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Recomputation — called by KpiProjectionRefreshJob
    // -------------------------------------------------------------------------

    /**
     * Recomputes and persists a projection for the given metric key.
     *
     * @param metricKey one of the constants in {@link KpiAggregationQueries}
     */
    @Transactional
    void recomputeAndPersist(String metricKey) {
        Instant now = clock.instant();
        log.info("analytics.recompute.start: metricKey={}", metricKey);

        KpiProjectionEntity entity = findOrCreate(metricKey,
                KpiAggregationQueries.SEGMENT_ALL, windowFor(metricKey), now);

        try {
            KpiAggregationQueries.AggregateResult result = runAggregation(metricKey);

            if (result != null) {
                if (redisCache != null) {
                    redisCache.evict(metricKey, entity.getSegmentKey(), entity.getWindowKey());
                }
                entity.setValue(result.value());
                entity.setNumerator(result.numerator());
                entity.setDenominator(result.denominator());
                entity.setSampleCount(result.sampleCount());
                entity.setDataAsOf(now);
                entity.bumpProjectionVersion();
                entity.setDegraded(false);
                entity.setDegradedReason(null);
            } else {
                entity.setDegraded(false);
                entity.setValue(java.math.BigDecimal.ZERO);
                entity.setSampleCount(0);
                entity.setDataAsOf(now);
                entity.bumpProjectionVersion();
            }

            repository.save(entity);
            metrics.recordDataAsOf(metricKey, now);

            if (redisCache != null) {
                redisCache.put(toProjection(entity));
            }
            log.info("analytics.recompute.complete: metricKey={} version={} value={}",
                    metricKey, entity.getProjectionVersion(), entity.getValue());

        } catch (Exception ex) {
            entity.setDegraded(true);
            entity.setDegradedReason(DegradationPolicy.REASON_REPLICA_UNAVAILABLE);
            entity.setDataAsOf(now);
            repository.save(entity);
            degradationPolicy.onReplicaFailure(
                    metricKey, entity.getSegmentKey(), entity.getWindowKey(), entity, ex);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @Nullable
    private KpiAggregationQueries.AggregateResult runAggregation(String metricKey) {
        return switch (metricKey) {
            case KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT      -> aggregationQueries.queryBacklogCount();
            case KpiAggregationQueries.METRIC_WO_COMPLETION_RATE_7D -> aggregationQueries.queryCompletionRate7d();
            case KpiAggregationQueries.METRIC_WO_SLA_COMPLIANCE_7D  -> aggregationQueries.querySlaCompliance7d();
            default -> {
                log.warn("analytics.recompute.unknown_metric: metricKey={}", metricKey);
                yield null;
            }
        };
    }

    private KpiProjectionEntity findOrCreate(
            String metricKey, String segmentKey, String windowKey, Instant now) {
        return repository.findByMetricKeyAndSegmentKeyAndWindowKey(metricKey, segmentKey, windowKey)
                .orElseGet(() -> repository.save(
                        new KpiProjectionEntity(UUID.randomUUID(), metricKey, segmentKey, windowKey, now)));
    }

    private String windowFor(String metricKey) {
        if (metricKey.endsWith("_7d")) return KpiAggregationQueries.WINDOW_ROLLING_7D;
        return KpiAggregationQueries.WINDOW_ALL_TIME;
    }

    private KpiProjection toProjection(KpiProjectionEntity e) {
        Instant now = clock.instant();
        long staleness = now.getEpochSecond() - e.getDataAsOf().getEpochSecond();
        return new KpiProjection(
                e.getMetricKey(), e.getSegmentKey(), e.getWindowKey(),
                e.getValue(), e.getNumerator(), e.getDenominator(),
                e.getSampleCount(), e.getMaturity(), e.getDataAsOf(),
                e.getProjectionVersion(), e.isDegraded(), e.getDegradedReason(),
                Math.max(0, staleness));
    }
}
