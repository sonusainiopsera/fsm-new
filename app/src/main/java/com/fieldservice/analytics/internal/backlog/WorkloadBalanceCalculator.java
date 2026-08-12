package com.fieldservice.analytics.internal.backlog;

import com.fieldservice.analytics.internal.KpiAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the workload balance coefficient of variation (CV) per active technician.
 *
 * <p><b>Formula</b>: CV = population_standard_deviation(assigned_hours) / mean(assigned_hours)
 *
 * <p><b>Convention — population, not sample:</b>
 * Population standard deviation (divide by N) is used rather than sample (N-1) because:
 * <ul>
 *   <li>We measure the complete set of currently active technicians, not a random sample.</li>
 *   <li>This makes CV directly comparable week-over-week even when team size is constant.</li>
 * </ul>
 * The formula is locked by {@code WorkloadBalanceCalculatorTest} with hand-computed datasets.
 *
 * <p><b>NOT_MEANINGFUL guard:</b>
 * <ul>
 *   <li>Fewer than {@value #MIN_SAMPLE} active technicians — CV is statistically meaningless
 *       for very small teams and must be labelled rather than rendered as a number.</li>
 *   <li>Mean assigned hours = 0 — division by zero; all active techs had no logged hours.</li>
 * </ul>
 *
 * <p><b>Guardrail direction:</b>
 * Compared against a reference value in {@code baseline_metric}.  Returns
 * {@code BASELINE_PENDING} when no baseline row exists.  Higher CV = worse balance,
 * so an increase is {@code WORSENED} and a decrease is {@code IMPROVED}.
 */
@Component
public class WorkloadBalanceCalculator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(WorkloadBalanceCalculator.class);

    static final String METRIC_KEY   = "workforce.workload_balance.cv";
    static final int    MIN_SAMPLE   = 3;
    static final int[]  WINDOW_DAYS  = {7, 30, 90};

    static final String NOT_MEANINGFUL   = "NOT_MEANINGFUL";
    static final String BASELINE_PENDING = "BASELINE_PENDING";
    static final String WORSENED         = "WORSENED";
    static final String IMPROVED         = "IMPROVED";
    static final String UNCHANGED        = "UNCHANGED";

    // Epsilon for "no material change" in CV comparison
    private static final double CV_EPSILON = 0.001;

    private final JdbcTemplate analyticsJdbc;
    private final Clock        clock;

    public WorkloadBalanceCalculator(
            @Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbc,
            Clock clock) {
        this.analyticsJdbc = analyticsJdbc;
        this.clock         = clock;
    }

    @Override
    public String metricKey() { return METRIC_KEY; }

    @Override
    public List<KpiAggregatorResult> compute() {
        Instant now = clock.instant();

        // Active technician IDs — query replica directly to stay consistent with analytics datasource
        List<UUID> activeTechIds = analyticsJdbc.queryForList(
                "SELECT id FROM technician WHERE active = TRUE", UUID.class);
        int techCount = activeTechIds.size();

        List<KpiAggregatorResult> results = new ArrayList<>();

        for (int windowDays : WINDOW_DAYS) {
            String  windowKey   = "P" + windowDays + "D";
            Instant windowStart = now.minus(windowDays, ChronoUnit.DAYS);

            if (techCount < MIN_SAMPLE) {
                results.add(notMeaningfulResult(windowKey, techCount, now));
                log.debug("workload_balance_not_meaningful window={} reason=insufficient_sample tech_count={}",
                        windowKey, techCount);
                continue;
            }

            Map<UUID, Long> minutesById = fetchLabourMinutes(windowStart);

            // All active techs contribute to the vector; those with no entries get zero hours
            double[] hoursVector = activeTechIds.stream()
                    .mapToDouble(id -> minutesById.getOrDefault(id, 0L) / 60.0)
                    .toArray();

            double mean = Arrays.stream(hoursVector).average().orElse(0.0);

            if (mean == 0.0) {
                results.add(notMeaningfulResult(windowKey, techCount, now));
                log.debug("workload_balance_not_meaningful window={} reason=zero_mean", windowKey);
                continue;
            }

            double populationStdDev = populationStdDev(hoursVector, mean);
            double cv               = populationStdDev / mean;
            String direction        = lookupGuardrailDirection(windowKey, cv);

            results.add(new KpiAggregatorResult(
                    "ALL",
                    windowKey,
                    BigDecimal.valueOf(populationStdDev).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(mean).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(cv).setScale(4, RoundingMode.HALF_UP),
                    techCount,
                    direction,
                    now
            ));

            log.debug("workload_balance_cv window={} cv={} direction={} techCount={}",
                    windowKey, cv, direction, techCount);
        }

        return results;
    }

    /**
     * Population standard deviation: {@code sqrt(sum((xi - mean)^2) / N)}.
     *
     * <p>Package-visible for direct testing in {@code WorkloadBalanceCalculatorTest}.
     */
    static double populationStdDev(double[] values, double mean) {
        double sumSqDiff = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSqDiff += diff * diff;
        }
        return Math.sqrt(sumSqDiff / values.length);
    }

    private Map<UUID, Long> fetchLabourMinutes(Instant windowStart) {
        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                "SELECT technician_id, SUM(minutes) AS total_minutes " +
                "FROM work_order_labour_entry " +
                "WHERE created_at >= ? AND technician_id IS NOT NULL " +
                "GROUP BY technician_id",
                Timestamp.from(windowStart));

        Map<UUID, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID techId = (UUID) row.get("technician_id");
            long mins   = toLong(row.get("total_minutes"));
            result.put(techId, mins);
        }
        return result;
    }

    private String lookupGuardrailDirection(String windowKey, double currentCv) {
        try {
            List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                    "SELECT baseline_value FROM baseline_metric " +
                    "WHERE metric_key = ? AND segment_key = 'ALL' AND window_key = ?",
                    METRIC_KEY, windowKey);

            if (rows.isEmpty() || rows.get(0).get("baseline_value") == null) {
                return BASELINE_PENDING;
            }

            double baseline = ((Number) rows.get(0).get("baseline_value")).doubleValue();
            double delta    = currentCv - baseline;

            if (Math.abs(delta) < CV_EPSILON) return UNCHANGED;
            return delta > 0 ? WORSENED : IMPROVED;

        } catch (Exception e) {
            log.warn("workload_balance_baseline_lookup_failed window={} error={}", windowKey, e.getMessage());
            return BASELINE_PENDING;
        }
    }

    private static KpiAggregatorResult notMeaningfulResult(String windowKey, int techCount,
                                                            Instant now) {
        return new KpiAggregatorResult(
                "ALL",
                windowKey,
                null,
                null,
                null,
                techCount,
                NOT_MEANINGFUL,
                now
        );
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
