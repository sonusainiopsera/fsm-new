package com.fieldservice.analytics.internal;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure function: computes resolution time statistics from pre-fetched aggregation rows (WO-162).
 *
 * <p>No Spring dependencies, no database access — inputs are value records so this class
 * is exhaustively unit-testable without a Spring context.
 *
 * <h3>Mean</h3>
 * Arithmetic mean of elapsed minutes per priority, rounded to 2 decimal places.
 * The ALL rollup is the weighted mean: sum(elapsed_minutes across all priorities) /
 * sum(total_closed across all priorities).
 *
 * <h3>Median</h3>
 * Computed in the database using {@code percentile_cont(0.5) WITHIN GROUP (ORDER BY elapsed_minutes)}
 * — a linear interpolation between the two middle values for even-count sets. The value
 * is carried through from the aggregation row unchanged. The Java fallback mirrors the
 * same definition by sorting values and interpolating at the 0.5 quantile:
 * {@code median = (sorted[n/2 - 1] + sorted[n/2]) / 2} for even n,
 * {@code median = sorted[n/2]} for odd n.
 * This matches PostgreSQL's percentile_cont definition for unit-test parity.
 *
 * <h3>No-data semantics</h3>
 * A segment with {@code totalClosed == 0} returns {@code mean = null} and
 * {@code median = null} so the UI renders "no data" rather than 0.
 */
final class ResolutionTimeCalculator {

    private ResolutionTimeCalculator() {}

    /**
     * Computes mean and median resolution time per priority and an ALL rollup.
     *
     * @param rows aggregated rows from {@link SlaAggregationRepository}
     * @return one {@link ResolutionResult} per priority plus one with priority="ALL"
     */
    static List<ResolutionResult> compute(List<SlaAggregationRepository.SlaAggRow> rows) {
        List<ResolutionResult> results = new ArrayList<>(rows.size() + 1);

        long   rollupTotal       = 0;
        double rollupWeightedSum = 0.0;

        for (SlaAggregationRepository.SlaAggRow row : rows) {
            if (row.totalClosed() == 0) {
                results.add(new ResolutionResult(row.priority(), null, null, 0));
                continue;
            }

            results.add(new ResolutionResult(
                    row.priority(),
                    row.meanResolutionMinutes() != null
                            ? row.meanResolutionMinutes().setScale(2, RoundingMode.HALF_UP)
                            : null,
                    row.medianResolutionMinutes() != null
                            ? row.medianResolutionMinutes().setScale(2, RoundingMode.HALF_UP)
                            : null,
                    (int) row.totalClosed()));

            if (row.meanResolutionMinutes() != null) {
                rollupTotal       += row.totalClosed();
                rollupWeightedSum += row.meanResolutionMinutes().doubleValue() * row.totalClosed();
            }
        }

        // Weighted ALL rollup
        BigDecimal rollupMean = null;
        if (rollupTotal > 0) {
            rollupMean = BigDecimal.valueOf(rollupWeightedSum / rollupTotal)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // Rollup median: not available from individual means — must be computed from raw SQL.
        // When the ALL row is requested, the aggregation query must be run at the ALL level.
        // Here we return null for rollup median as it cannot be derived from per-priority
        // percentiles without the underlying distribution.
        results.add(new ResolutionResult(
                SlaMetricKeys.SEGMENT_ALL,
                rollupMean,
                null,  // see note above — requires a separate percentile_cont over all priorities
                (int) rollupTotal));

        return results;
    }

    /**
     * Java-side median computation for unit-test parity with PostgreSQL's percentile_cont(0.5).
     *
     * <p>Mirrors PostgreSQL definition: for even n, returns the average of the two middle
     * values. For odd n, returns the middle value. Input values must be sorted ascending.
     *
     * <p>Exposed package-private for direct unit testing.
     *
     * @param sortedValues sorted ascending list of elapsed minute values
     * @return median value; null if list is empty
     */
    @Nullable
    static BigDecimal computeMedian(List<BigDecimal> sortedValues) {
        if (sortedValues.isEmpty()) return null;
        int n = sortedValues.size();
        if (n % 2 == 1) {
            return sortedValues.get(n / 2).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal lower = sortedValues.get(n / 2 - 1);
        BigDecimal upper = sortedValues.get(n / 2);
        return lower.add(upper)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    // ── Result record ──────────────────────────────────────────────────────────

    /**
     * Resolution time result for one segment.
     *
     * @param priority     priority tier key (or "ALL")
     * @param meanMinutes  arithmetic mean elapsed minutes; null = no data
     * @param medianMinutes median elapsed minutes; null = no data or rollup
     * @param sampleCount  number of closed work orders in the window
     */
    record ResolutionResult(
            String priority,
            @Nullable BigDecimal meanMinutes,
            @Nullable BigDecimal medianMinutes,
            int sampleCount) {}
}
