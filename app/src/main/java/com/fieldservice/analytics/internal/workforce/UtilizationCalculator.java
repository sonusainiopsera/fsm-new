package com.fieldservice.analytics.internal.workforce;

import com.fieldservice.analytics.internal.KpiAggregator;
import com.fieldservice.analytics.internal.sla.BaselineMetricRepository;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.LabourRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ShiftRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes {@code workforce.utilization.rate}: logged field-task hours divided by
 * total rostered shift hours, per technician per ISO week and as a team rollup.
 *
 * <p><strong>Formula (single source of truth per AC-1):</strong>
 * <pre>
 *   per-technician:  utilization = fieldMinutes / shiftMinutes
 *   team rollup:     utilization = SUM(fieldMinutes) / SUM(shiftMinutes)
 * </pre>
 *
 * <p>The team rollup is the <em>sum of numerators over the sum of denominators</em>
 * (volume-weighted). It is NOT the mean of per-technician rates.
 * Example: tech A 40/50 h = 80%, tech B 10/10 h = 100% → mean-of-ratios = 90%,
 * but correct rollup = 50/60 h = 83.3%. A unit test (AC-1) asserts the two differ
 * and that this calculator uses the correct sum-of-numerators formula.
 *
 * <p><strong>Partial-week handling (AC-3):</strong> a week is flagged {@code partial_bucket = TRUE}
 * if the ISO week boundary falls within the query window (i.e. the week started before
 * or ends after the window). Partial buckets are excluded from trend comparisons by default.
 *
 * <p><strong>Missing hours-worked source (constraint):</strong> if no shift row exists for
 * a technician-week, the row is marked {@code maturity = INCOMPLETE_DATA} and is excluded
 * from the weighted ALL rollup. A structured warning is logged. Default shift lengths are
 * never substituted.
 *
 * <p><strong>Data classification:</strong> projection rows store technician UUID only;
 * display names are resolved at render time, satisfying BR-23.
 *
 * <p>The 70% utilization target is stored only as a reference line display value.
 * No test asserts that production utilization equals or exceeds 70%.
 */
@Component
public class UtilizationCalculator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(UtilizationCalculator.class);

    public static final String METRIC_KEY             = "workforce.utilization.rate";
    public static final String MATURITY_BASELINE_PENDING = "BASELINE_PENDING";
    public static final String MATURITY_INCOMPLETE    = "INCOMPLETE_DATA";
    /** Reference line value (70%). Stored as metadata only, never a test threshold. */
    public static final double REFERENCE_LINE_PCT     = 0.70;

    static final int[] WINDOW_DAYS = {7, 30, 90};

    private final WorkforceAggregationRepository repo;
    private final ActiveTechnicianDayResolver    dayResolver;
    private final BaselineMetricRepository       baselineRepo;
    private final Clock                          clock;

    UtilizationCalculator(WorkforceAggregationRepository repo,
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

            List<LabourRow> labourRows = repo.queryLabourByTechWeek(windowStart, windowEnd);
            List<ShiftRow>  shiftRows  = repo.queryShiftByTechWeek(windowStart, windowEnd);
            List<LabourRow> priorLabour = repo.queryLabourByTechWeek(priorStart, windowStart);
            List<ShiftRow>  priorShift  = repo.queryShiftByTechWeek(priorStart, windowStart);

            LocalDate wStart = windowStart.atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate wEnd   = windowEnd.atZone(ZoneOffset.UTC).toLocalDate();
            LocalDate pStart = priorStart.atZone(ZoneOffset.UTC).toLocalDate();

            // Index by (technician, week)
            Map<String, LabourRow> labourIndex = indexLabour(labourRows);
            Map<String, ShiftRow>  shiftIndex  = indexShift(shiftRows);
            Map<String, LabourRow> priorLabourIndex = indexLabour(priorLabour);
            Map<String, ShiftRow>  priorShiftIndex  = indexShift(priorShift);

            // Full cohort = all technicians appearing in either labour or shift rows
            java.util.Set<UUID> allTechs = dayResolver.resolveAllTechnicians(labourRows, shiftRows);

            // Team rollup accumulators (sum-of-numerators approach)
            long teamFieldMinutes = 0;
            long teamShiftMinutes = 0;
            Instant latestAsOf    = null;

            for (UUID techId : allTechs) {
                // Collect all ISO weeks this technician appears in
                java.util.Set<LocalDate> weeks = collectWeeks(techId, labourRows, shiftRows);

                for (LocalDate weekStart : weeks) {
                    boolean isPartial = isPartialWeek(weekStart, wStart, wEnd);

                    String lKey = labourKey(techId, weekStart);
                    String sKey = shiftKey(techId, weekStart);

                    LabourRow lr = labourIndex.get(lKey);
                    ShiftRow  sr = shiftIndex.get(sKey);

                    long fieldMin = lr != null ? lr.fieldMinutes() : 0L;
                    Instant asOf  = lr != null ? lr.dataAsOf() : now;

                    if (sr == null || sr.shiftMinutes() == 0) {
                        // Missing shift data — mark incomplete, exclude from rollup
                        log.warn("workforce_utilization_incomplete technician_id={} week={} window={}",
                                techId, weekStart, windowKey);
                        String segKey = "TECH:" + techId + ":WEEK:" + weekStart;
                        results.add(new KpiAggregatorResult(
                                segKey, windowKey,
                                BigDecimal.valueOf(fieldMin), BigDecimal.ZERO, null,
                                1, MATURITY_INCOMPLETE, asOf != null ? asOf : now,
                                isPartial, true));
                        continue;
                    }

                    long shiftMin = sr.shiftMinutes();

                    // Clamp: field minutes cannot exceed shift minutes (data-quality guard)
                    boolean overUtilized = fieldMin > shiftMin;
                    if (overUtilized) {
                        log.warn("workforce_utilization_over_100pct technician_id={} week={} field_min={} shift_min={}",
                                techId, weekStart, fieldMin, shiftMin);
                        // Flag but do NOT clamp — report actual value with degraded flag
                    }

                    BigDecimal rate = ratio(fieldMin, shiftMin);
                    String maturity = resolveMaturity("TECH:" + techId);

                    String segKey = "TECH:" + techId + ":WEEK:" + weekStart;
                    results.add(new KpiAggregatorResult(
                            segKey, windowKey,
                            BigDecimal.valueOf(fieldMin), BigDecimal.valueOf(shiftMin),
                            rate, 1, maturity, asOf != null ? asOf : now,
                            isPartial, false));

                    if (!isPartial) {
                        teamFieldMinutes += fieldMin;
                        teamShiftMinutes += shiftMin;
                        if (asOf != null && (latestAsOf == null || asOf.isAfter(latestAsOf))) {
                            latestAsOf = asOf;
                        }
                    }
                }
            }

            // Team rollup (sum-of-numerators, NOT mean-of-ratios — AC-1)
            if (teamShiftMinutes > 0) {
                BigDecimal teamRate = ratio(teamFieldMinutes, teamShiftMinutes);
                String teamMaturity = resolveMaturity("ALL");
                Instant teamAsOf    = latestAsOf != null ? latestAsOf : now;

                results.add(new KpiAggregatorResult(
                        "ALL", windowKey,
                        BigDecimal.valueOf(teamFieldMinutes), BigDecimal.valueOf(teamShiftMinutes),
                        teamRate, allTechs.size(), teamMaturity, teamAsOf,
                        false, false));

                // Prior-period delta for ALL
                long priorField = sumField(priorLabour, priorShift);
                long priorShiftTotal = sumShift(priorShift);
                if (priorShiftTotal > 0) {
                    BigDecimal priorRate = ratio(priorField, priorShiftTotal);
                    BigDecimal delta = teamRate == null || priorRate == null ? null
                            : teamRate.subtract(priorRate).setScale(4, RoundingMode.HALF_UP);
                    results.add(new KpiAggregatorResult(
                            "DELTA:ALL", windowKey,
                            teamRate, priorRate, delta,
                            (int) Math.min(Integer.MAX_VALUE, priorShift.size()),
                            teamMaturity, teamAsOf, false, false));
                }
            }
        }

        log.info("workforce_utilization_computed metric_key={} result_count={} duration_ms={}",
                METRIC_KEY, results.size(), System.currentTimeMillis() - startMs);
        return results;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Returns utilization ratio rounded to 4 decimal places, or null for zero denominator.
     */
    static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) return null;
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), 4, RoundingMode.HALF_UP);
    }

    private boolean isPartialWeek(LocalDate weekStart, LocalDate windowStart, LocalDate windowEnd) {
        LocalDate weekEnd = weekStart.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        // Partial if the week starts before the window or ends after the window
        return weekStart.isBefore(windowStart) || !weekEnd.isBefore(windowEnd);
    }

    private String resolveMaturity(String segmentKey) {
        return baselineRepo.findByMetricKeyAndSegmentKey(METRIC_KEY, segmentKey)
                .map(b -> "BASELINE_PENDING") // simplified — numeric attainment resolved by dashboard layer
                .orElse(MATURITY_BASELINE_PENDING);
    }

    private static java.util.Set<LocalDate> collectWeeks(
            UUID techId, List<LabourRow> labourRows, List<ShiftRow> shiftRows) {
        java.util.Set<LocalDate> weeks = new java.util.HashSet<>();
        labourRows.stream().filter(r -> techId.equals(r.technicianId()))
                .map(LabourRow::isoWeekStart).filter(java.util.Objects::nonNull).forEach(weeks::add);
        shiftRows.stream().filter(r -> techId.equals(r.technicianId()))
                .map(ShiftRow::isoWeekStart).filter(java.util.Objects::nonNull).forEach(weeks::add);
        return weeks;
    }

    private static Map<String, LabourRow> indexLabour(List<LabourRow> rows) {
        Map<String, LabourRow> map = new HashMap<>();
        for (LabourRow r : rows) { map.put(labourKey(r.technicianId(), r.isoWeekStart()), r); }
        return map;
    }

    private static Map<String, ShiftRow> indexShift(List<ShiftRow> rows) {
        Map<String, ShiftRow> map = new HashMap<>();
        for (ShiftRow r : rows) { map.put(shiftKey(r.technicianId(), r.isoWeekStart()), r); }
        return map;
    }

    private static String labourKey(UUID t, LocalDate w) { return t + ":" + w; }
    private static String shiftKey(UUID t, LocalDate w)  { return t + ":" + w; }

    private static long sumField(List<LabourRow> labour, List<ShiftRow> shift) {
        java.util.Set<UUID> techs = shift.stream().map(ShiftRow::technicianId).collect(java.util.stream.Collectors.toSet());
        return labour.stream().filter(r -> techs.contains(r.technicianId()))
                .mapToLong(LabourRow::fieldMinutes).sum();
    }

    private static long sumShift(List<ShiftRow> shift) {
        return shift.stream().mapToLong(ShiftRow::shiftMinutes).sum();
    }
}
