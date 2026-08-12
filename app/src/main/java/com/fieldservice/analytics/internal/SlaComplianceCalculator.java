package com.fieldservice.analytics.internal;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure function: computes SLA compliance rates from pre-fetched aggregation rows (WO-162).
 *
 * <p>No Spring dependencies, no database access — inputs are value records so this class
 * is exhaustively unit-testable without a Spring context.
 *
 * <h3>Compliance formula</h3>
 * {@code compliance_rate = compliant_count / total_closed} for each priority segment.
 * The ALL-priorities rollup is the weighted aggregate:
 * {@code rollup_rate = SUM(compliant_count_per_priority) / SUM(total_closed_per_priority)}.
 * It is NOT a mean of per-priority rates, which would be biased by unequal segment sizes.
 *
 * <h3>No-data semantics</h3>
 * A segment with {@code total_closed == 0} returns {@code value = null} so the UI
 * renders "no data" rather than "0%" (AC-6 edge case). When ALL segments have no data,
 * the rollup also returns null.
 *
 * <h3>Reconciliation invariant</h3>
 * For every segment: {@code compliant_count + breach_count <= total_closed}.
 * (A work order may be breached on response but not on resolution — hence ≤, not =.)
 *
 * <h3>Policy-missing segment</h3>
 * Segments in {@code rows} whose priority key is absent from {@code policies} are
 * returned as degraded (degraded=true, reason=POLICY_MISSING) and excluded from the
 * weighted rollup.
 */
final class SlaComplianceCalculator {

    private SlaComplianceCalculator() {}

    /**
     * Computes compliance results for each priority segment and an ALL rollup.
     *
     * @param rows      aggregated rows from {@link SlaAggregationRepository}
     * @param policies  active SLA policy keys (priority string → present=has policy)
     * @param reasons   breach reason breakdown rows for the same window
     * @return one {@link SegmentResult} per priority plus one with priority="ALL"
     */
    static List<SegmentResult> compute(
            List<SlaAggregationRepository.SlaAggRow> rows,
            java.util.Set<String> policies,
            List<SlaAggregationRepository.BreachReasonRow> reasons) {

        // Index breach reasons by priority
        Map<String, List<SlaAggregationRepository.BreachReasonRow>> reasonsByPriority =
                reasons.stream().collect(Collectors.groupingBy(
                        SlaAggregationRepository.BreachReasonRow::priority));

        List<SegmentResult> results = new ArrayList<>(rows.size() + 1);

        long rollupTotal     = 0;
        long rollupCompliant = 0;
        long rollupBreaches  = 0;
        long rollupOverrun   = 0;
        boolean anyData      = false;

        for (SlaAggregationRepository.SlaAggRow row : rows) {
            boolean policyMissing = !policies.contains(row.priority());

            List<BreachReasonSummary> breachReasons =
                    buildBreachReasons(reasonsByPriority.getOrDefault(row.priority(), List.of()));

            SegmentResult seg = new SegmentResult(
                    row.priority(),
                    row.totalClosed() == 0 ? null : row.complianceRate(),
                    BigDecimal.valueOf(row.compliantCount()),
                    BigDecimal.valueOf(row.totalClosed()),
                    row.totalClosed(),
                    row.breachCount(),
                    row.totalOverrunMinutes(),
                    row.meanOverrunMinutes(),
                    breachReasons,
                    policyMissing,
                    policyMissing ? "POLICY_MISSING" : null);

            results.add(seg);

            if (!policyMissing && row.totalClosed() > 0) {
                rollupTotal     += row.totalClosed();
                rollupCompliant += row.compliantCount();
                rollupBreaches  += row.breachCount();
                rollupOverrun   += row.totalOverrunMinutes();
                anyData          = true;
            }
        }

        // Weighted ALL rollup
        BigDecimal rollupRate = null;
        if (anyData && rollupTotal > 0) {
            rollupRate = BigDecimal.valueOf(rollupCompliant)
                    .divide(BigDecimal.valueOf(rollupTotal), 4, RoundingMode.HALF_UP);
        }

        results.add(new SegmentResult(
                SlaMetricKeys.SEGMENT_ALL,
                rollupRate,
                BigDecimal.valueOf(rollupCompliant),
                BigDecimal.valueOf(rollupTotal),
                rollupTotal,
                rollupBreaches,
                rollupOverrun,
                rollupTotal == 0 ? null :
                    BigDecimal.valueOf(rollupOverrun).divide(
                        BigDecimal.valueOf(rollupBreaches == 0 ? 1 : rollupBreaches), 2, RoundingMode.HALF_UP),
                buildBreachReasons(reasons), // all-priorities breach reasons
                false,
                null));

        return results;
    }

    /**
     * Computes the prior-period delta: {@code current.value - prior.value} per segment.
     *
     * <p>When either period has no data ({@code value == null}), the delta is null
     * (returns no-comparison rather than a misleading 100% improvement — AC-6 edge case).
     *
     * @return map of priority → delta value (positive = improvement, negative = regression)
     */
    static Map<String, @Nullable BigDecimal> computeDeltas(
            List<SegmentResult> current, List<SegmentResult> prior) {

        Map<String, BigDecimal> priorMap = prior.stream()
                .filter(s -> s.value() != null)
                .collect(Collectors.toMap(SegmentResult::priority, SegmentResult::value));

        Map<String, BigDecimal> deltas = new LinkedHashMap<>();
        for (SegmentResult s : current) {
            BigDecimal priorVal = priorMap.get(s.priority());
            if (s.value() == null || priorVal == null) {
                deltas.put(s.priority(), null);
            } else {
                deltas.put(s.priority(), s.value().subtract(priorVal));
            }
        }
        return deltas;
    }

    private static List<BreachReasonSummary> buildBreachReasons(
            List<SlaAggregationRepository.BreachReasonRow> rows) {
        Map<String, long[]> map = new LinkedHashMap<>();
        for (SlaAggregationRepository.BreachReasonRow r : rows) {
            map.computeIfAbsent(r.reasonCode(), k -> new long[2]);
            map.get(r.reasonCode())[0] += r.breachCount();
            map.get(r.reasonCode())[1] += r.totalOverrunMinutes();
        }
        return map.entrySet().stream()
                .map(e -> new BreachReasonSummary(
                        e.getKey(),
                        e.getValue()[0],
                        e.getValue()[0] == 0 ? null :
                            BigDecimal.valueOf(e.getValue()[1])
                                .divide(BigDecimal.valueOf(e.getValue()[0]), 2, RoundingMode.HALF_UP)))
                .toList();
    }

    // ── Result records ─────────────────────────────────────────────────────────

    /**
     * Computed SLA compliance result for one segment.
     *
     * @param priority           priority tier key (or "ALL" for rollup)
     * @param value              compliance rate in [0,1]; null = no data
     * @param numerator          compliant count
     * @param denominator        total closed count
     * @param totalClosed        total closed count as a primitive
     * @param breachCount        total breach count
     * @param totalOverrunMinutes cumulative overrun across all breaches
     * @param meanOverrunMinutes  mean overrun per breach; null if no breaches
     * @param breachReasons      breach counts grouped by reason code
     * @param degraded           true if the SLA policy row is missing for this priority
     * @param degradedReason     reason code string; null if not degraded
     */
    record SegmentResult(
            String priority,
            @Nullable BigDecimal value,
            BigDecimal numerator,
            BigDecimal denominator,
            long totalClosed,
            long breachCount,
            long totalOverrunMinutes,
            @Nullable BigDecimal meanOverrunMinutes,
            List<BreachReasonSummary> breachReasons,
            boolean degraded,
            @Nullable String degradedReason) {}

    /** Breach count and mean overrun for a single reason code. */
    record BreachReasonSummary(
            String reasonCode,
            long count,
            @Nullable BigDecimal meanOverrunMinutes) {}
}
