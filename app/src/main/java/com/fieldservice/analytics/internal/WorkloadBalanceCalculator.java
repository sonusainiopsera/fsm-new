package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes workload balance as the coefficient of variation (CV) of assigned hours per
 * active technician (WO-165).
 *
 * <h3>Formula</h3>
 * <pre>
 *   hours_per_tech = SUM(labour_time_record.minutes) / 60.0  per active technician
 *   mean           = SUM(hours_per_tech) / N
 *   pop_stddev     = SQRT( SUM((x - mean)^2) / N )          -- POPULATION not sample
 *   cv             = pop_stddev / mean
 * </pre>
 *
 * <h3>Standard deviation convention</h3>
 * Population standard deviation is used deliberately (divides by N, not N-1). This is
 * documented here and locked by unit tests against hand-computed reference datasets.
 * Rationale: we are measuring the entire active team, not sampling from it.
 *
 * <h3>Small-sample guard</h3>
 * Returns {@link CvResult#NOT_MEANINGFUL} with a labelled reason when:
 * <ul>
 *   <li>Fewer than {@value #MIN_TECHNICIANS} active technicians — CV is statistically
 *       meaningless with a tiny denominator.</li>
 *   <li>Mean assigned hours is zero — division by zero; labelled separately so the
 *       operations team knows there is no labour logged, not just a small team.</li>
 * </ul>
 *
 * <h3>Active technician definition</h3>
 * An active technician is one with at least one current assignment (is_current=true).
 * This is consistent with the assignment resolution used elsewhere in the analytics module.
 */
@Component
class WorkloadBalanceCalculator {

    private static final Logger log = LoggerFactory.getLogger(WorkloadBalanceCalculator.class);

    /** Minimum number of active technicians for a meaningful CV. */
    static final int MIN_TECHNICIANS = 3;

    private static final int DEFAULT_WINDOW_DAYS = 7;

    private final BacklogAggregationRepository repository;

    WorkloadBalanceCalculator(BacklogAggregationRepository repository) {
        this.repository = repository;
    }

    /**
     * Computes the workload balance CV for the rolling 7-day window.
     *
     * @return a {@link CvResult} — either a numeric CV or a NOT_MEANINGFUL result with a reason
     */
    CvResult computeCv() {
        return computeCv(DEFAULT_WINDOW_DAYS);
    }

    /**
     * Computes the workload balance CV over the given window in days.
     * Package-visible for testing with custom window lengths.
     */
    CvResult computeCv(int windowDays) {
        List<Map<String, Object>> rows = repository.queryTechnicianHoursInWindow(windowDays);

        if (rows.isEmpty()) {
            return CvResult.notMeaningful(NotMeaningfulReason.NO_ACTIVE_TECHNICIANS);
        }

        // Extract hours per technician (minutes / 60.0)
        List<Double> hours = rows.stream()
                .map(r -> {
                    Object minutes = r.get("total_minutes");
                    return minutes instanceof Number n ? n.doubleValue() / 60.0 : 0.0;
                })
                .collect(Collectors.toList());

        int n = hours.size();
        if (n < MIN_TECHNICIANS) {
            log.debug("workload.cv.not_meaningful: n={} reason=TOO_FEW_TECHNICIANS", n);
            return CvResult.notMeaningful(NotMeaningfulReason.TOO_FEW_TECHNICIANS);
        }

        double sum  = hours.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / n;

        if (mean == 0.0) {
            log.debug("workload.cv.not_meaningful: n={} reason=ZERO_MEAN", n);
            return CvResult.notMeaningful(NotMeaningfulReason.ZERO_MEAN);
        }

        // Population standard deviation: SQRT( SUM((x - mean)^2) / N )
        double sumSquaredDiffs = hours.stream()
                .mapToDouble(x -> (x - mean) * (x - mean))
                .sum();
        double populationStdDev = Math.sqrt(sumSquaredDiffs / n);
        double cv = populationStdDev / mean;

        log.debug("workload.cv.computed: n={} mean={} stdDev={} cv={}", n,
                String.format("%.4f", mean), String.format("%.4f", populationStdDev), String.format("%.4f", cv));
        return CvResult.value(
                BigDecimal.valueOf(cv).setScale(4, RoundingMode.HALF_UP),
                n,
                BigDecimal.valueOf(mean).setScale(4, RoundingMode.HALF_UP));
    }

    /**
     * Converts a {@link CvResult} to a {@link KpiAggregationQueries.AggregateResult} for
     * persistence via {@code KpiProjectionService}. Returns {@code null} if NOT_MEANINGFUL
     * so the projection stores zero with the degraded-reason set.
     */
    KpiAggregationQueries.AggregateResult toAggregateResult(CvResult result) {
        if (!result.meaningful()) return null;
        return new KpiAggregationQueries.AggregateResult(
                result.cv(),
                result.cv(),
                BigDecimal.ONE,
                result.technicianCount());
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    enum NotMeaningfulReason {
        TOO_FEW_TECHNICIANS,
        NO_ACTIVE_TECHNICIANS,
        ZERO_MEAN
    }

    /**
     * Result of a CV computation.
     *
     * <p>When {@code meaningful() == true}, {@link #cv()}, {@link #technicianCount()} and
     * {@link #meanHoursPerTechnician()} are populated. Otherwise {@link #reason()} describes why.
     */
    record CvResult(
            boolean meaningful,
            BigDecimal cv,
            int technicianCount,
            BigDecimal meanHoursPerTechnician,
            NotMeaningfulReason reason) {

        static CvResult value(BigDecimal cv, int n, BigDecimal mean) {
            return new CvResult(true, cv, n, mean, null);
        }

        static CvResult notMeaningful(NotMeaningfulReason reason) {
            return new CvResult(false, null, 0, null, reason);
        }
    }
}
