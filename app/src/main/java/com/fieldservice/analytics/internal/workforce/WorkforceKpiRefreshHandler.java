package com.fieldservice.analytics.internal.workforce;

import com.fieldservice.analytics.internal.AnalyticsRedisCache;
import com.fieldservice.analytics.internal.BaselineMetricStore;
import com.fieldservice.analytics.internal.KpiProjectionEntity;
import com.fieldservice.analytics.internal.KpiProjectionRepository;
import com.fieldservice.analytics.internal.TrendPointWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates multi-segment, multi-window workforce KPI projection upserts (WO-163).
 *
 * <p>Called by {@link com.fieldservice.analytics.internal.KpiProjectionService} when any
 * workforce metric key is drained from the debounce registry. One call recomputes all
 * windows (7/30/90 days) and all segments (per-technician + ALL team rollup).
 *
 * <p>Since no roster/shift table exists in the current schema, all utilization rows are
 * marked {@code is_incomplete_data = true}: the denominator (shift hours) cannot be
 * resolved. Jobs-per-day rows use logged-time active days as the denominator proxy and
 * are also marked incomplete. Both flags are surfaced to the dashboard so operations
 * managers understand the limitation.
 *
 * <p>Prior-period delta rows use {@code windowKey = "{WINDOW}_DELTA"} (consistent with SLA
 * delta convention) and carry current value as numerator and prior value as denominator.
 */
@Component
public class WorkforceKpiRefreshHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkforceKpiRefreshHandler.class);

    private final WorkforceAggregationRepository aggregationRepository;
    private final KpiProjectionRepository        projectionRepository;
    private final BaselineMetricStore            baselineStore;
    private final ActiveTechnicianDayResolver    activeDayResolver;
    private final TrendPointWriter               trendPointWriter;
    private final ZoneId                         kpiZone;

    @Nullable
    private final AnalyticsRedisCache redisCache;

    WorkforceKpiRefreshHandler(
            WorkforceAggregationRepository aggregationRepository,
            KpiProjectionRepository projectionRepository,
            BaselineMetricStore baselineStore,
            ActiveTechnicianDayResolver activeDayResolver,
            TrendPointWriter trendPointWriter,
            @Value("${app.analytics.kpi-zone:UTC}") String kpiZoneId,
            @Autowired(required = false) AnalyticsRedisCache redisCache) {
        this.aggregationRepository = aggregationRepository;
        this.projectionRepository  = projectionRepository;
        this.baselineStore         = baselineStore;
        this.activeDayResolver     = activeDayResolver;
        this.trendPointWriter      = trendPointWriter;
        this.kpiZone               = ZoneId.of(kpiZoneId);
        this.redisCache            = redisCache;
    }

    /**
     * Recomputes all workforce metrics for all windows and segments.
     *
     * @param triggerMetricKey the metric key that triggered the refresh
     * @param now              reference instant (Clock.instant())
     */
    @Transactional
    public void recomputeAll(String triggerMetricKey, Instant now) {
        log.info("workforce.kpi.recompute.start: trigger={}", triggerMetricKey);
        long startMs = System.currentTimeMillis();
        int rowsUpserted = 0;

        for (String window : WorkforceMetricKeys.WINDOWS) {
            int days = WorkforceMetricKeys.windowDays(window);

            // ── Utilization ──────────────────────────────────────────────────────
            List<WorkforceAggregationRepository.LabourWeekRow> labourRows =
                    aggregationRepository.queryLabourByTechnicianWeek(now, days, kpiZone);

            // No roster table → empty shift map; all rows marked incomplete
            Map<UtilizationCalculator.TechWeekKey, Integer> shiftMap = Collections.emptyMap();
            List<UtilizationCalculator.TechWeekResult> utilResults =
                    UtilizationCalculator.computePerTechnician(labourRows, shiftMap);
            UtilizationCalculator.TeamRollupResult utilTeam =
                    UtilizationCalculator.computeTeamRollup(utilResults);

            // Upsert per-technician utilization rows
            for (UtilizationCalculator.TechWeekResult r : utilResults) {
                String segKey  = r.technicianId().toString();
                String maturity = determineMaturity(WorkforceMetricKeys.UTILIZATION_RATE, segKey, window);
                rowsUpserted += upsertProjection(
                        WorkforceMetricKeys.UTILIZATION_RATE, segKey, window,
                        r.value(), r.value() != null ? BigDecimal.valueOf(r.fieldMinutes()) : null,
                        r.shiftMinutes() != null ? BigDecimal.valueOf(r.shiftMinutes()) : null,
                        1, maturity, r.incompleteData(), false, r.degraded(),
                        r.degradedReason(), now);
            }

            // Upsert team rollup utilization
            String utilMaturity = determineMaturity(
                    WorkforceMetricKeys.UTILIZATION_RATE, WorkforceMetricKeys.SEGMENT_ALL, window);
            rowsUpserted += upsertProjection(
                    WorkforceMetricKeys.UTILIZATION_RATE, WorkforceMetricKeys.SEGMENT_ALL, window,
                    utilTeam.value(), utilTeam.numerator(), utilTeam.denominator(),
                    utilTeam.sampleCount(), utilMaturity,
                    utilTeam.incompleteData(), false,
                    utilTeam.degraded(), utilTeam.degradedReason(), now);

            // Trend point for ALL rollup utilization
            if (utilTeam.value() != null) {
                trendPointWriter.writeTodayIfAbsent(
                        WorkforceMetricKeys.UTILIZATION_RATE,
                        WorkforceMetricKeys.SEGMENT_ALL,
                        utilTeam.value(), utilTeam.sampleCount());
            }

            // ── Throughput (jobs/day) ─────────────────────────────────────────
            List<WorkforceAggregationRepository.ClosureRow> closureRows =
                    aggregationRepository.queryClosuresByTechnicianDate(now, days, kpiZone);
            List<WorkforceAggregationRepository.ActiveDayRow> activeDayRows =
                    aggregationRepository.queryActiveDaysByTechnician(now, days, kpiZone);
            Map<UUID, Integer> activeDaysByTech = activeDayResolver.resolve(activeDayRows);
            boolean rosterAbsent = activeDayResolver.isIncompleteData();

            List<ThroughputCalculator.TechResult> techThruput =
                    ThroughputCalculator.computePerTechnician(closureRows, activeDaysByTech);
            ThroughputCalculator.TeamRollupResult teamThruput =
                    ThroughputCalculator.computeTeamRollup(closureRows, activeDaysByTech);

            // Upsert per-technician throughput rows
            for (ThroughputCalculator.TechResult r : techThruput) {
                String segKey  = r.technicianId().toString();
                String maturity = determineMaturity(WorkforceMetricKeys.JOBS_PER_DAY, segKey, window);
                rowsUpserted += upsertProjection(
                        WorkforceMetricKeys.JOBS_PER_DAY, segKey, window,
                        r.jobsPerDay(),
                        BigDecimal.valueOf(r.closureCount()),
                        BigDecimal.valueOf(r.activeDays()),
                        r.activeDays(), maturity,
                        rosterAbsent, false,
                        false, null, now);
            }

            // Upsert team rollup throughput
            String thruMaturity = determineMaturity(
                    WorkforceMetricKeys.JOBS_PER_DAY, WorkforceMetricKeys.SEGMENT_ALL, window);
            rowsUpserted += upsertProjection(
                    WorkforceMetricKeys.JOBS_PER_DAY, WorkforceMetricKeys.SEGMENT_ALL, window,
                    teamThruput.value(), teamThruput.numerator(), teamThruput.denominator(),
                    teamThruput.techCount(), thruMaturity,
                    rosterAbsent, false,
                    false, null, now);

            // Trend point for ALL rollup throughput
            if (teamThruput.value() != null) {
                trendPointWriter.writeTodayIfAbsent(
                        WorkforceMetricKeys.JOBS_PER_DAY,
                        WorkforceMetricKeys.SEGMENT_ALL,
                        teamThruput.value(), teamThruput.techCount());
            }

            // ── Prior-period deltas ───────────────────────────────────────────
            // Compute prior window (same length, ending at window start)
            Instant priorEnd   = now.minus(days, java.time.temporal.ChronoUnit.DAYS);

            List<WorkforceAggregationRepository.LabourWeekRow> priorLabourRows =
                    aggregationRepository.queryLabourByTechnicianWeek(priorEnd, days, kpiZone);
            UtilizationCalculator.TeamRollupResult priorUtilTeam =
                    UtilizationCalculator.computeTeamRollup(
                            UtilizationCalculator.computePerTechnician(priorLabourRows, shiftMap));

            BigDecimal utilDelta = (utilTeam.value() != null && priorUtilTeam.value() != null)
                    ? utilTeam.value().subtract(priorUtilTeam.value()) : null;
            rowsUpserted += upsertProjection(
                    WorkforceMetricKeys.UTILIZATION_RATE, WorkforceMetricKeys.SEGMENT_ALL,
                    WorkforceMetricKeys.deltaWindowKey(window),
                    utilDelta, utilTeam.value(), priorUtilTeam.value(),
                    0, utilMaturity, utilTeam.incompleteData(), false,
                    false, null, now);

            List<WorkforceAggregationRepository.ClosureRow> priorClosureRows =
                    aggregationRepository.queryClosuresByTechnicianDate(priorEnd, days, kpiZone);
            List<WorkforceAggregationRepository.ActiveDayRow> priorActiveDayRows =
                    aggregationRepository.queryActiveDaysByTechnician(priorEnd, days, kpiZone);
            Map<UUID, Integer> priorActiveDaysByTech = activeDayResolver.resolve(priorActiveDayRows);
            ThroughputCalculator.TeamRollupResult priorTeamThruput =
                    ThroughputCalculator.computeTeamRollup(priorClosureRows, priorActiveDaysByTech);

            BigDecimal thruDelta = (teamThruput.value() != null && priorTeamThruput.value() != null)
                    ? teamThruput.value().subtract(priorTeamThruput.value()) : null;
            rowsUpserted += upsertProjection(
                    WorkforceMetricKeys.JOBS_PER_DAY, WorkforceMetricKeys.SEGMENT_ALL,
                    WorkforceMetricKeys.deltaWindowKey(window),
                    thruDelta, teamThruput.value(), priorTeamThruput.value(),
                    0, thruMaturity, rosterAbsent, false,
                    false, null, now);

            log.info("workforce.kpi.window.done: window={} rows={}", window, rowsUpserted);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("workforce.kpi.recompute.complete: trigger={} rowsUpserted={} durationMs={}",
                triggerMetricKey, rowsUpserted, durationMs);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private String determineMaturity(String metricKey, String segmentKey, String windowKey) {
        BigDecimal baseline = baselineStore.findBaseline(metricKey, segmentKey, windowKey);
        return baseline == null
                ? WorkforceMetricKeys.MATURITY_BASELINE_PENDING
                : WorkforceMetricKeys.MATURITY_CURRENT;
    }

    @SuppressWarnings("ParameterNumber")
    private int upsertProjection(
            String metricKey, String segmentKey, String windowKey,
            @Nullable BigDecimal value,
            @Nullable BigDecimal numerator,
            @Nullable BigDecimal denominator,
            int sampleCount,
            String maturity,
            boolean incompleteData,
            boolean partialWeek,
            boolean degraded,
            @Nullable String degradedReason,
            Instant now) {
        try {
            KpiProjectionEntity entity = projectionRepository
                    .findByMetricKeyAndSegmentKeyAndWindowKey(metricKey, segmentKey, windowKey)
                    .orElseGet(() -> projectionRepository.save(
                            new KpiProjectionEntity(
                                    UUID.randomUUID(), metricKey, segmentKey, windowKey, now)));

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
            entity.setIncompleteData(incompleteData);
            entity.setPartialWeek(partialWeek);
            projectionRepository.save(entity);
            return 1;
        } catch (Exception ex) {
            log.error("workforce.kpi.upsert.error: metricKey={} segment={} window={} — {}",
                    metricKey, segmentKey, windowKey, ex.getMessage());
            return 0;
        }
    }
}
