package com.fieldservice.analytics.internal.workforce;

import com.fieldservice.analytics.internal.KpiAggregator;
import com.fieldservice.analytics.internal.sla.BaselineMetricRepository;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ClosureRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.LabourRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ShiftRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Computes {@code workforce.jobs_per_day}: closure events divided by active technician-days.
 *
 * <p><strong>Formula (single source of truth per AC-2):</strong>
 * <pre>
 *   per-technician:  jobs_per_day = closureCount / activeDays
 *   team rollup:     jobs_per_day = SUM(closureCount) / SUM(activeDays)
 * </pre>
 *
 * <p><strong>Active technician-day definition (documented in {@link ActiveTechnicianDayResolver}):</strong>
 * A day is active if the technician had a rostered shift OR any logged field time on that date.
 *
 * <p><strong>Zero-active-day exclusion (AC-2):</strong> technicians with zero active days in
 * the window are excluded from both the per-technician output and the team denominator.
 * They must not be counted as a zero-throughput day — that would dilute the team rate.
 *
 * <p><strong>Team rollup:</strong> SUM(closures across all active technicians) divided by
 * SUM(activeDays across the same technicians). NOT a mean of per-technician rates.
 *
 * <p><strong>Data classification:</strong> segment keys store technician UUID only, satisfying BR-23.
 */
@Component
public class ThroughputCalculator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(ThroughputCalculator.class);

    public static final String METRIC_KEY = "workforce.jobs_per_day";

    static final int[] WINDOW_DAYS = {7, 30, 90};

    private final WorkforceAggregationRepository repo;
    private final ActiveTechnicianDayResolver    dayResolver;
    private final BaselineMetricRepository       baselineRepo;
    private final Clock                          clock;

    ThroughputCalculator(WorkforceAggregationRepository repo,
                          ActiveTechnicianDayResolver dayResolver,
                          BaselineMetricRepository baselineRepo,
                          Clock clock) {
        this.repo         = repo;
        this.dayResolver  = dayResolver;
        this.baselineRepo = baselineRepo;
        this.clock        = clock;
    }

    @Override
    public String metricKey() { return METRIC_KEY; }

    @Override
    public List<KpiAggregatorResult> compute() {
        long startMs = System.currentTimeMillis();
        Instant now  = clock.instant();

        List<KpiAggregatorResult> results = new ArrayList<>();

        for (int days : WINDOW_DAYS) {
            String  windowKey   = "P" + days + "D";
            Instant windowStart = now.minus(days, ChronoUnit.DAYS);
            Instant windowEnd   = now;
            Instant priorStart  = windowStart.minus(days, ChronoUnit.DAYS);

            List<ClosureRow> closureRows = repo.queryClosuresByTechDate(windowStart, windowEnd);
            List<LabourRow>  labourRows  = repo.queryLabourByTechWeek(windowStart, windowEnd);
            List<ShiftRow>   shiftRows   = repo.queryShiftByTechWeek(windowStart, windowEnd);

            List<ClosureRow> priorClosures = repo.queryClosuresByTechDate(priorStart, windowStart);
            List<LabourRow>  priorLabour   = repo.queryLabourByTechWeek(priorStart, windowStart);
            List<ShiftRow>   priorShift    = repo.queryShiftByTechWeek(priorStart, windowStart);

            LocalDate wStart = windowStart.atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate wEnd   = windowEnd.atZone(ZoneOffset.UTC).toLocalDate();

            Map<UUID, Long> activeDays = dayResolver.resolveActiveDays(
                    labourRows, shiftRows, wStart, wEnd);

            // Closure counts per technician
            Map<UUID, Long>    closureByTech  = sumClosures(closureRows);
            Map<UUID, Instant> latestByTech   = latestClosureDate(closureRows);

            // Team accumulators
            long teamClosures  = 0;
            long teamActiveDays = 0;
            Instant latestAsOf  = null;

            Set<UUID> techsWithClosures = dayResolver.resolveTechsWithClosures(closureRows);

            for (UUID techId : techsWithClosures) {
                Long aDays = activeDays.get(techId);
                if (aDays == null || aDays == 0) {
                    // Zero active days — excluded from denominator per AC-2
                    log.debug("workforce_throughput_zero_active_days_excluded technician_id={} window={}",
                            techId, windowKey);
                    continue;
                }

                long closures = closureByTech.getOrDefault(techId, 0L);
                Instant asOf  = latestByTech.getOrDefault(techId, now);
                BigDecimal jpd = ratio(closures, aDays);
                String maturity = resolveMaturity("TECH:" + techId);

                String segKey = "TECH:" + techId;
                results.add(new KpiAggregatorResult(
                        segKey, windowKey,
                        BigDecimal.valueOf(closures), BigDecimal.valueOf(aDays),
                        jpd, (int) Math.min(closures, Integer.MAX_VALUE), maturity, asOf,
                        false, false));

                teamClosures   += closures;
                teamActiveDays += aDays;
                if (latestAsOf == null || asOf.isAfter(latestAsOf)) latestAsOf = asOf;
            }

            // Team rollup
            if (teamActiveDays > 0) {
                BigDecimal teamJpd  = ratio(teamClosures, teamActiveDays);
                String     maturity = resolveMaturity("ALL");
                Instant    asOf     = latestAsOf != null ? latestAsOf : now;

                results.add(new KpiAggregatorResult(
                        "ALL", windowKey,
                        BigDecimal.valueOf(teamClosures), BigDecimal.valueOf(teamActiveDays),
                        teamJpd, (int) Math.min(teamClosures, Integer.MAX_VALUE),
                        maturity, asOf, false, false));

                // Prior-period delta
                Map<UUID, Long> priorActiveDays = dayResolver.resolveActiveDays(
                        priorLabour, priorShift,
                        priorStart.atZone(ZoneOffset.UTC).toLocalDate(),
                        windowStart.atZone(ZoneOffset.UTC).toLocalDate());
                long priorTotalClosures = 0;
                long priorTotalActiveDays = 0;
                for (UUID t : dayResolver.resolveTechsWithClosures(priorClosures)) {
                    Long pd = priorActiveDays.get(t);
                    if (pd != null && pd > 0) {
                        priorTotalClosures  += sumClosures(priorClosures).getOrDefault(t, 0L);
                        priorTotalActiveDays += pd;
                    }
                }
                if (priorTotalActiveDays > 0) {
                    BigDecimal priorJpd = ratio(priorTotalClosures, priorTotalActiveDays);
                    BigDecimal delta    = teamJpd == null || priorJpd == null ? null
                            : teamJpd.subtract(priorJpd).setScale(4, RoundingMode.HALF_UP);
                    results.add(new KpiAggregatorResult(
                            "DELTA:ALL", windowKey,
                            teamJpd, priorJpd, delta,
                            (int) Math.min(priorTotalClosures, Integer.MAX_VALUE),
                            maturity, asOf, false, false));
                }
            }
        }

        log.info("workforce_throughput_computed metric_key={} result_count={} duration_ms={}",
                METRIC_KEY, results.size(), System.currentTimeMillis() - startMs);
        return results;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) return null;
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), 4, RoundingMode.HALF_UP);
    }

    private String resolveMaturity(String segmentKey) {
        return baselineRepo.findByMetricKeyAndSegmentKey(METRIC_KEY, segmentKey)
                .map(b -> "BASELINE_PENDING")
                .orElse("BASELINE_PENDING");
    }

    private static Map<UUID, Long> sumClosures(List<ClosureRow> rows) {
        Map<UUID, Long> map = new HashMap<>();
        for (ClosureRow r : rows) {
            map.merge(r.technicianId(), r.closureCount(), Long::sum);
        }
        return map;
    }

    private static Map<UUID, Instant> latestClosureDate(List<ClosureRow> rows) {
        Map<UUID, Instant> map = new HashMap<>();
        for (ClosureRow r : rows) {
            if (r.dataAsOf() != null) {
                map.merge(r.technicianId(), r.dataAsOf(),
                        (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        return map;
    }
}
