package com.fieldservice.analytics.internal;

import com.fieldservice.sla.SlaPolicyProvider;
import com.fieldservice.sla.SlaPolicyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates multi-segment, multi-window SLA KPI projection upserts (WO-162).
 *
 * <p>Called by {@link KpiProjectionService} when any SLA metric key is drained from
 * the debounce registry. One call recomputes all windows (7/30/90 days) and all priority
 * segments simultaneously, then upserts each (metric, segment, window) row.
 *
 * <p>SLA policies are loaded once at the start of the recomputation so a mid-run
 * policy change cannot produce an inconsistent segment. Segments with no matching
 * policy row are marked POLICY_MISSING and excluded from the ALL rollup.
 *
 * <p>Prior-period delta rows use {@code windowKey = "{WINDOW}_DELTA"} and carry the
 * current value as numerator and prior period value as denominator, so the widget API
 * reads precomputed comparisons without recomputing.
 *
 * <p>Target-attainment maturity: when no row exists in baseline_metric for a segment,
 * the projection's maturity field is set to {@code BASELINE_PENDING} (BR-30). When a
 * baseline row exists, maturity is {@code CURRENT}.
 */
@Component
class SlaKpiRefreshHandler {

    private static final Logger log = LoggerFactory.getLogger(SlaKpiRefreshHandler.class);

    private final SlaAggregationRepository   aggregationRepository;
    private final KpiProjectionRepository    projectionRepository;
    private final BaselineMetricStore        baselineStore;
    private final SlaPolicyProvider          slaPolicyProvider;
    private final TrendPointWriter           trendPointWriter;

    @Nullable
    private final AnalyticsRedisCache        redisCache;

    SlaKpiRefreshHandler(
            SlaAggregationRepository aggregationRepository,
            KpiProjectionRepository projectionRepository,
            BaselineMetricStore baselineStore,
            SlaPolicyProvider slaPolicyProvider,
            TrendPointWriter trendPointWriter,
            @Autowired(required = false) AnalyticsRedisCache redisCache) {
        this.aggregationRepository = aggregationRepository;
        this.projectionRepository  = projectionRepository;
        this.baselineStore         = baselineStore;
        this.slaPolicyProvider     = slaPolicyProvider;
        this.trendPointWriter      = trendPointWriter;
        this.redisCache            = redisCache;
    }

    /**
     * Recomputes all SLA metrics for all windows and segments.
     *
     * @param triggerMetricKey the metric key that triggered the refresh
     * @param now              reference instant (typically Clock.instant())
     */
    @Transactional
    void recomputeAll(String triggerMetricKey, Instant now) {
        log.info("sla.kpi.recompute.start: trigger={}", triggerMetricKey);
        long startMs = System.currentTimeMillis();
        int rowsUpserted = 0;

        // Load all active SLA policies once — cached for the full recomputation
        Set<String> activePolicies = resolveActivePolicies(now);

        for (String window : SlaMetricKeys.WINDOWS) {
            int days = SlaMetricKeys.windowDays(window);

            List<SlaAggregationRepository.SlaAggRow> currentRows =
                    aggregationRepository.queryCurrentWindow(now, days);
            List<SlaAggregationRepository.SlaAggRow> priorRows =
                    aggregationRepository.queryPriorWindow(now, days);
            List<SlaAggregationRepository.BreachReasonRow> reasonRows =
                    aggregationRepository.queryBreachReasons(now, days);

            // Compute compliance segments
            List<SlaComplianceCalculator.SegmentResult> complianceSegments =
                    SlaComplianceCalculator.compute(currentRows, activePolicies, reasonRows);
            List<SlaComplianceCalculator.SegmentResult> priorComplianceSegments =
                    SlaComplianceCalculator.compute(priorRows, activePolicies, List.of());
            Map<String, BigDecimal> complianceDeltas =
                    SlaComplianceCalculator.computeDeltas(complianceSegments, priorComplianceSegments);

            // Compute resolution time segments
            List<ResolutionTimeCalculator.ResolutionResult> resolutionSegments =
                    ResolutionTimeCalculator.compute(currentRows);
            List<ResolutionTimeCalculator.ResolutionResult> priorResolutionSegments =
                    ResolutionTimeCalculator.compute(priorRows);

            // Upsert compliance projections
            for (SlaComplianceCalculator.SegmentResult seg : complianceSegments) {
                String maturity = determineMaturity(
                        SlaMetricKeys.COMPLIANCE_RATE, seg.priority(), window);
                rowsUpserted += upsertProjection(
                        SlaMetricKeys.COMPLIANCE_RATE, seg.priority(), window,
                        seg.value(), seg.numerator(), seg.denominator(),
                        (int) seg.totalClosed(), maturity, seg.degraded(),
                        seg.degradedReason(), now);

                // Delta row
                BigDecimal delta = complianceDeltas.get(seg.priority());
                BigDecimal priorVal = priorComplianceSegments.stream()
                        .filter(p -> p.priority().equals(seg.priority()))
                        .findFirst()
                        .map(SlaComplianceCalculator.SegmentResult::value)
                        .orElse(null);
                rowsUpserted += upsertProjection(
                        SlaMetricKeys.COMPLIANCE_RATE, seg.priority(),
                        SlaMetricKeys.deltaWindowKey(window),
                        delta, seg.value(), priorVal,
                        0, maturity, seg.degraded(), seg.degradedReason(), now);

                // Breach count
                rowsUpserted += upsertProjection(
                        SlaMetricKeys.BREACH_COUNT, seg.priority(), window,
                        BigDecimal.valueOf(seg.breachCount()),
                        BigDecimal.valueOf(seg.breachCount()),
                        BigDecimal.valueOf(seg.totalClosed()),
                        (int) seg.totalClosed(), maturity, seg.degraded(),
                        seg.degradedReason(), now);
            }

            // Upsert resolution time projections
            for (ResolutionTimeCalculator.ResolutionResult seg : resolutionSegments) {
                String maturity = determineMaturity(
                        SlaMetricKeys.RESOLUTION_MEAN, seg.priority(), window);

                rowsUpserted += upsertProjection(
                        SlaMetricKeys.RESOLUTION_MEAN, seg.priority(), window,
                        seg.meanMinutes(), seg.meanMinutes(), null,
                        seg.sampleCount(), maturity, false, null, now);

                rowsUpserted += upsertProjection(
                        SlaMetricKeys.RESOLUTION_MEDIAN, seg.priority(), window,
                        seg.medianMinutes(), seg.medianMinutes(), null,
                        seg.sampleCount(), maturity, false, null, now);

                // Resolution mean delta
                BigDecimal priorMean = priorResolutionSegments.stream()
                        .filter(p -> p.priority().equals(seg.priority()))
                        .findFirst()
                        .map(ResolutionTimeCalculator.ResolutionResult::meanMinutes)
                        .orElse(null);
                BigDecimal meanDelta = (seg.meanMinutes() != null && priorMean != null)
                        ? seg.meanMinutes().subtract(priorMean) : null;
                rowsUpserted += upsertProjection(
                        SlaMetricKeys.RESOLUTION_MEAN, seg.priority(),
                        SlaMetricKeys.deltaWindowKey(window),
                        meanDelta, seg.meanMinutes(), priorMean,
                        0, maturity, false, null, now);
            }

            // Trend point for ALL rollup (compliance)
            complianceSegments.stream()
                    .filter(s -> SlaMetricKeys.SEGMENT_ALL.equals(s.priority()) && s.value() != null)
                    .findFirst()
                    .ifPresent(all -> trendPointWriter.writeTodayIfAbsent(
                            SlaMetricKeys.COMPLIANCE_RATE,
                            SlaMetricKeys.SEGMENT_ALL,
                            all.value(),
                            (int) all.totalClosed()));

            log.info("sla.kpi.window.done: window={} rows={}", window, rowsUpserted);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("sla.kpi.recompute.complete: trigger={} rowsUpserted={} durationMs={}",
                triggerMetricKey, rowsUpserted, durationMs);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private Set<String> resolveActivePolicies(Instant now) {
        // Probe the three most common priority values from the system.
        // Any priority NOT in sla_policy is caught by SlaPolicyUnavailableException.
        // Since we don't know which priorities exist without querying the WO table,
        // we rely on the aggregation query returning whatever priorities exist and then
        // validate each against sla_policy inside SlaComplianceCalculator via the set.
        //
        // Here we query distinct priorities from work_order and test each against the provider.
        // Priorities that throw SlaPolicyUnavailableException are excluded from the set
        // (they will be POLICY_MISSING segments).
        try {
            List<String> allPriorities = aggregationRepository.queryDistinctClosedPriorities();
            return allPriorities.stream()
                    .filter(p -> {
                        try {
                            slaPolicyProvider.resolveActivePolicy(p, now);
                            return true;
                        } catch (SlaPolicyUnavailableException ex) {
                            log.warn("sla.kpi.policy_missing: priority={}", p);
                            return false;
                        }
                    })
                    .collect(Collectors.toSet());
        } catch (Exception ex) {
            log.warn("sla.kpi.priorities.error — {}", ex.getMessage());
            return Set.of();
        }
    }

    private String determineMaturity(String metricKey, String segmentKey, String windowKey) {
        BigDecimal baseline = baselineStore.findBaseline(metricKey, segmentKey, windowKey);
        return baseline == null
                ? SlaMetricKeys.MATURITY_BASELINE_PENDING
                : SlaMetricKeys.MATURITY_CURRENT;
    }

    private int upsertProjection(
            String metricKey, String segmentKey, String windowKey,
            @Nullable BigDecimal value,
            @Nullable BigDecimal numerator,
            @Nullable BigDecimal denominator,
            int sampleCount,
            String maturity,
            boolean degraded,
            @Nullable String degradedReason,
            Instant now) {
        try {
            KpiProjectionEntity entity = projectionRepository
                    .findByMetricKeyAndSegmentKeyAndWindowKey(metricKey, segmentKey, windowKey)
                    .orElseGet(() -> projectionRepository.save(
                            new KpiProjectionEntity(UUID.randomUUID(), metricKey, segmentKey, windowKey, now)));

            if (redisCache != null) {
                redisCache.evict(metricKey, segmentKey, windowKey);
            }

            entity.setValue(value);
            entity.setNumerator(numerator);
            entity.setDenominator(denominator);
            entity.setSampleCount(sampleCount);
            entity.setMaturity(maturity);
            entity.setDataAsOf(now);
            entity.bumpProjectionVersion();
            entity.setDegraded(degraded);
            entity.setDegradedReason(degradedReason);
            projectionRepository.save(entity);
            return 1;
        } catch (Exception ex) {
            log.error("sla.kpi.upsert.error: metricKey={} segment={} window={} — {}",
                    metricKey, segmentKey, windowKey, ex.getMessage());
            return 0;
        }
    }
}
