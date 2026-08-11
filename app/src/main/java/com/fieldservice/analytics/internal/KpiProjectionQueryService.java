package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implements the public {@link KpiProjectionQuery} port.
 *
 * <p>Read strategy:
 * <ol>
 *   <li>Load the projection entity from the DB to get the current {@code projection_version}
 *       (fast index lookup on the unique key constraint).</li>
 *   <li>Try Redis using the version-qualified key; cache hit returns without further DB work.</li>
 *   <li>Cache miss: build the DTO from the DB entity, populate Redis, return.</li>
 *   <li>DB failure: {@link DegradationPolicy} attempts Redis last-known lookup;
 *       returned with {@code degraded = true}. Never throws to the caller.</li>
 * </ol>
 */
@Service
class KpiProjectionQueryService implements KpiProjectionQuery {

    private static final Logger log = LoggerFactory.getLogger(KpiProjectionQueryService.class);

    private final KpiProjectionRepository projectionRepository;
    private final KpiProjectionCache      cache;
    private final DegradationPolicy       degradationPolicy;
    private final AnalyticsMetrics        metrics;
    private final Clock                   clock;

    KpiProjectionQueryService(KpiProjectionRepository projectionRepository,
                               KpiProjectionCache      cache,
                               DegradationPolicy       degradationPolicy,
                               AnalyticsMetrics        metrics,
                               Clock                   clock) {
        this.projectionRepository = projectionRepository;
        this.cache                = cache;
        this.degradationPolicy    = degradationPolicy;
        this.metrics              = metrics;
        this.clock                = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KpiProjection> findByKey(String metricKey, String segmentKey, String windowKey) {
        try {
            Optional<KpiProjectionEntity> entity =
                    projectionRepository.findByMetricKeyAndSegmentKeyAndWindowKey(
                            metricKey, segmentKey, windowKey);

            if (entity.isEmpty()) {
                return Optional.empty();
            }

            KpiProjectionEntity e = entity.get();
            long staleness = staleness(e);

            Optional<KpiProjection> cached = cache.get(metricKey, segmentKey, windowKey,
                                                        e.getProjectionVersion());
            if (cached.isPresent()) {
                metrics.recordCacheHit(metricKey);
                metrics.recordStaleness(metricKey, staleness);
                return cached;
            }

            metrics.recordCacheMiss(metricKey);
            KpiProjection dto = e.toDto(staleness);
            cache.put(dto);
            metrics.recordStaleness(metricKey, staleness);
            return Optional.of(dto);

        } catch (Exception ex) {
            log.warn("analytics_query_failure metric_key={} segment={} window={} error={}",
                    metricKey, segmentKey, windowKey, ex.getMessage());
            return degradationPolicy.handleQueryFailure(metricKey, segmentKey, windowKey, ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<KpiProjection> findByMetricKey(String metricKey) {
        try {
            List<KpiProjectionEntity> entities = projectionRepository.findByMetricKey(metricKey);
            return entities.stream()
                    .map(e -> {
                        long staleness = staleness(e);
                        Optional<KpiProjection> cached = cache.get(
                                metricKey, e.getSegmentKey(), e.getWindowKey(), e.getProjectionVersion());
                        if (cached.isPresent()) {
                            metrics.recordCacheHit(metricKey);
                            return cached.get();
                        }
                        metrics.recordCacheMiss(metricKey);
                        KpiProjection dto = e.toDto(staleness);
                        cache.put(dto);
                        return dto;
                    })
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.warn("analytics_list_failure metric_key={} error={}", metricKey, ex.getMessage());
            return List.of();
        }
    }

    private long staleness(KpiProjectionEntity e) {
        long s = clock.instant().getEpochSecond() - e.getDataAsOf().getEpochSecond();
        return Math.max(0L, s);
    }
}
