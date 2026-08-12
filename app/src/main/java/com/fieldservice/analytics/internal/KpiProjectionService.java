package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import com.fieldservice.analytics.TrendPointDto;
import com.fieldservice.analytics.internal.workforce.WorkforceKpiRefreshHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
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
    private final FirstTimeFixCalculator ftfCalculator;
    private final BacklogCalculator backlogCalculator;
    private final WorkloadBalanceCalculator workloadCalculator;
    private final TrendPointWriter trendPointWriter;
    private final DegradationPolicy degradationPolicy;
    private final AnalyticsMetrics metrics;
    private final Clock clock;
    private final SlaKpiRefreshHandler slaKpiRefreshHandler;
    private final WorkforceKpiRefreshHandler workforceKpiRefreshHandler;
    private final BaselineMetricStore baselineMetricStore;
    private final JdbcTemplate primaryJdbcTemplate;

    @Nullable
    private final AnalyticsRedisCache redisCache;

    KpiProjectionService(
            KpiProjectionRepository repository,
            KpiAggregationQueries aggregationQueries,
            FirstTimeFixCalculator ftfCalculator,
            BacklogCalculator backlogCalculator,
            WorkloadBalanceCalculator workloadCalculator,
            TrendPointWriter trendPointWriter,
            DegradationPolicy degradationPolicy,
            AnalyticsMetrics metrics,
            Clock clock,
            SlaKpiRefreshHandler slaKpiRefreshHandler,
            WorkforceKpiRefreshHandler workforceKpiRefreshHandler,
            BaselineMetricStore baselineMetricStore,
            JdbcTemplate primaryJdbcTemplate,
            @Nullable @org.springframework.beans.factory.annotation.Autowired(required = false)
            AnalyticsRedisCache redisCache) {
        this.repository = repository;
        this.aggregationQueries = aggregationQueries;
        this.ftfCalculator = ftfCalculator;
        this.backlogCalculator = backlogCalculator;
        this.workloadCalculator = workloadCalculator;
        this.trendPointWriter = trendPointWriter;
        this.degradationPolicy = degradationPolicy;
        this.metrics = metrics;
        this.clock = clock;
        this.slaKpiRefreshHandler = slaKpiRefreshHandler;
        this.workforceKpiRefreshHandler = workforceKpiRefreshHandler;
        this.baselineMetricStore = baselineMetricStore;
        this.primaryJdbcTemplate = primaryJdbcTemplate;
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
        // SLA metrics produce multiple (segment, window) rows — delegated to the SLA handler
        if (SlaMetricKeys.ALL_METRIC_KEYS.contains(metricKey)) {
            slaKpiRefreshHandler.recomputeAll(metricKey, clock.instant());
            return;
        }

        // WO-163: workforce metrics produce multiple (segment, window) rows — delegated to workforce handler
        if ("workforce.utilization.rate".equals(metricKey) || "workforce.jobs_per_day".equals(metricKey)) {
            workforceKpiRefreshHandler.recomputeAll(metricKey, clock.instant());
            return;
        }

        Instant now = clock.instant();
        log.info("analytics.recompute.start: metricKey={}", metricKey);

        KpiProjectionEntity entity = findOrCreate(metricKey,
                segmentFor(metricKey), windowFor(metricKey), now);

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
            case QualityMetricKeys.FTF_MATURED                      -> ftfCalculator.queryMaturedRate();
            case QualityMetricKeys.FTF_PROVISIONAL                  -> ftfCalculator.queryProvisionalRate();
            case QualityMetricKeys.REPEAT_VISIT_COUNT               -> ftfCalculator.queryRepeatVisitCount();
            case QualityMetricKeys.UNCLASSIFIABLE_COUNT             -> ftfCalculator.queryUnclassifiableCount();
            case BacklogMetricKeys.BACKLOG_OPEN_COUNT               -> runBacklogOpenCount();
            case BacklogMetricKeys.BACKLOG_ON_HOLD_COUNT            -> runBacklogOnHoldCount();
            case BacklogMetricKeys.WORKLOAD_BALANCE_CV              -> runWorkloadBalanceCv();
            default -> {
                log.warn("analytics.recompute.unknown_metric: metricKey={}", metricKey);
                yield null;
            }
        };
    }

    @Nullable
    private KpiAggregationQueries.AggregateResult runBacklogOpenCount() {
        KpiAggregationQueries.AggregateResult result = backlogCalculator.queryOpenBacklogTotal();
        if (result != null) {
            trendPointWriter.writeTodayIfAbsent(
                    BacklogMetricKeys.BACKLOG_OPEN_COUNT,
                    KpiAggregationQueries.SEGMENT_ALL,
                    result.value(),
                    result.sampleCount());
        }
        return result;
    }

    @Nullable
    private KpiAggregationQueries.AggregateResult runBacklogOnHoldCount() {
        KpiAggregationQueries.AggregateResult result = backlogCalculator.queryOnHoldCount();
        if (result != null) {
            trendPointWriter.writeTodayIfAbsent(
                    BacklogMetricKeys.BACKLOG_ON_HOLD_COUNT,
                    KpiAggregationQueries.SEGMENT_ALL,
                    result.value(),
                    result.sampleCount());
        }
        return result;
    }

    @Nullable
    private KpiAggregationQueries.AggregateResult runWorkloadBalanceCv() {
        WorkloadBalanceCalculator.CvResult cvResult = workloadCalculator.computeCv();
        if (!cvResult.meaningful()) {
            log.info("workload.cv.not_meaningful: reason={}", cvResult.reason());
            return null;
        }
        return workloadCalculator.toAggregateResult(cvResult);
    }

    private String segmentFor(String metricKey) {
        return QualityMetricKeys.FTF_PROVISIONAL.equals(metricKey)
                ? QualityMetricKeys.SEGMENT_PROVISIONAL
                : KpiAggregationQueries.SEGMENT_ALL;
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

    // -------------------------------------------------------------------------
    // KpiProjectionQuery — trend points and baseline (WO-166)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<TrendPointDto> findRecentTrendPoints(String metricKey, String segmentKey, int limit) {
        try {
            return primaryJdbcTemplate.query(
                    "SELECT bucket_date, value FROM kpi_trend_point " +
                    "WHERE metric_key = ? AND segment_key = ? " +
                    "ORDER BY bucket_date DESC LIMIT ?",
                    (rs, i) -> new TrendPointDto(
                            rs.getDate("bucket_date").toLocalDate(),
                            rs.getBigDecimal("value")),
                    metricKey, segmentKey, limit);
        } catch (Exception ex) {
            log.warn("analytics.trend_points.read.error: metricKey={} segment={} — {}",
                    metricKey, segmentKey, ex.getMessage());
            return List.of();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> findBaseline(String metricKey, String segmentKey, String windowKey) {
        return Optional.ofNullable(baselineMetricStore.findBaseline(metricKey, segmentKey, windowKey));
    }
}
