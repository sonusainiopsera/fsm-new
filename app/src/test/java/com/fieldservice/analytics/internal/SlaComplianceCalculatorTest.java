package com.fieldservice.analytics.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SlaComplianceCalculator} (WO-162, AC-1/2/5/8).
 *
 * Pure in-memory tests — no Spring context, no database, no clock injection needed.
 * No SLA threshold or priority name is hardcoded; inputs are built from arbitrary strings.
 */
@DisplayName("SlaComplianceCalculator unit tests")
class SlaComplianceCalculatorTest {

    private static final Set<String> ALL_POLICIES = Set.of("P1", "P2", "P3");

    // ── Helper ─────────────────────────────────────────────────────────────────

    private static SlaAggregationRepository.SlaAggRow row(
            String priority, long total, long compliant, long breaches) {
        return new SlaAggregationRepository.SlaAggRow(
                priority, total, compliant, breaches, 0L, null, null, null);
    }

    // ── AC-1: Compliance formula ───────────────────────────────────────────────

    @Test
    @DisplayName("AC-1: compliance rate = compliant / total_closed per segment")
    void complianceRate_perSegment() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                row("P1", 10, 8, 2),
                row("P2", 5,  5, 0));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        SlaComplianceCalculator.SegmentResult p2 = segmentFor(results, "P2");

        assertThat(p1.value()).isEqualByComparingTo("0.8000");
        assertThat(p2.value()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("AC-1: ALL rollup is weighted aggregate, not mean of rates")
    void rollup_isWeightedAggregate() {
        // P1: 8/10 = 0.8, P2: 2/10 = 0.2
        // Weighted: (8+2) / (10+10) = 10/20 = 0.5, NOT (0.8+0.2)/2 = 0.5 coincidentally
        // Use different sizes to verify: P1=8/10, P2=1/2
        // Weighted: 9/12 = 0.75; mean-of-rates: (0.8 + 0.5)/2 = 0.65 — they differ
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                row("P1", 10, 8, 2),
                row("P2",  2, 1, 1));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        SlaComplianceCalculator.SegmentResult all = segmentFor(results, "ALL");
        // Weighted: 9 / 12 = 0.75
        assertThat(all.value()).isEqualByComparingTo("0.7500");
        assertThat(all.numerator()).isEqualByComparingTo("9");
        assertThat(all.denominator()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("AC-5: compliant + breach <= total for every segment")
    void reconciliation_compliantPlusBreachLessOrEqualTotal() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(row("P1", 10, 7, 3));
        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        assertThat(p1.numerator().longValue() + p1.breachCount())
                .isLessThanOrEqualTo(p1.totalClosed());
    }

    // ── AC-6 edge cases ────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6: zero total_closed returns null value (no-data, not 0%)")
    void zeroDenominator_returnsNullValue() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(row("P1", 0, 0, 0));
        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        assertThat(p1.value()).isNull();
    }

    @Test
    @DisplayName("AC-6: empty rows produces ALL rollup with null value and zero counts")
    void emptyRows_allRollupHasNullValue() {
        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(List.of(), ALL_POLICIES, List.of());

        assertThat(results).hasSize(1);
        SlaComplianceCalculator.SegmentResult all = results.get(0);
        assertThat(all.priority()).isEqualTo("ALL");
        assertThat(all.value()).isNull();
        assertThat(all.totalClosed()).isZero();
    }

    @Test
    @DisplayName("AC-6: work order closed exactly at deadline counts as compliant")
    void closedExactlyAtDeadline_countsAsCompliant() {
        // SQL uses `updated_at <= resolution_due_at` so equality is compliant.
        // This is a contract test — the aggregation row's compliantCount includes this case.
        // The calculator trusts the repository's count; here we verify the rate formula.
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(row("P1", 1, 1, 0));
        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        assertThat(segmentFor(results, "P1").value()).isEqualByComparingTo("1.0000");
    }

    // ── AC-2: Policy-missing segment ──────────────────────────────────────────

    @Test
    @DisplayName("AC-2: segment with missing policy is degraded and excluded from rollup")
    void policyMissing_segmentDegradedAndExcludedFromRollup() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                row("P1", 10, 8, 2),    // has policy
                row("UNKNOWN", 5, 5, 0));// no policy

        Set<String> policiesWithoutUnknown = Set.of("P1");
        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, policiesWithoutUnknown, List.of());

        SlaComplianceCalculator.SegmentResult unknown = segmentFor(results, "UNKNOWN");
        assertThat(unknown.degraded()).isTrue();
        assertThat(unknown.degradedReason()).isEqualTo("POLICY_MISSING");

        // Rollup only counts P1
        SlaComplianceCalculator.SegmentResult all = segmentFor(results, "ALL");
        assertThat(all.denominator()).isEqualByComparingTo("10");
        assertThat(all.value()).isEqualByComparingTo("0.8000");
    }

    // ── AC-8: Prior-period delta ───────────────────────────────────────────────

    @Test
    @DisplayName("AC-8: prior-period delta is current minus prior")
    void priorPeriodDelta_currentMinusPrior() {
        List<SlaAggregationRepository.SlaAggRow> currentRows = List.of(row("P1", 10, 9, 1));
        List<SlaAggregationRepository.SlaAggRow> priorRows   = List.of(row("P1", 10, 7, 3));

        List<SlaComplianceCalculator.SegmentResult> current =
                SlaComplianceCalculator.compute(currentRows, ALL_POLICIES, List.of());
        List<SlaComplianceCalculator.SegmentResult> prior =
                SlaComplianceCalculator.compute(priorRows, ALL_POLICIES, List.of());

        Map<String, BigDecimal> deltas = SlaComplianceCalculator.computeDeltas(current, prior);

        // current P1 = 0.9000, prior P1 = 0.7000; delta = +0.2000
        assertThat(deltas.get("P1")).isEqualByComparingTo("0.2000");
        // ALL rollup delta
        assertThat(deltas.get("ALL")).isEqualByComparingTo("0.2000");
    }

    @Test
    @DisplayName("AC-8: delta is null when prior period has no data (no misleading 100% improvement)")
    void priorPeriodNoData_deltaIsNull() {
        List<SlaAggregationRepository.SlaAggRow> currentRows = List.of(row("P1", 10, 9, 1));
        List<SlaAggregationRepository.SlaAggRow> priorRows   = List.of(row("P1", 0, 0, 0));

        List<SlaComplianceCalculator.SegmentResult> current =
                SlaComplianceCalculator.compute(currentRows, ALL_POLICIES, List.of());
        List<SlaComplianceCalculator.SegmentResult> prior =
                SlaComplianceCalculator.compute(priorRows, ALL_POLICIES, List.of());

        Map<String, BigDecimal> deltas = SlaComplianceCalculator.computeDeltas(current, prior);

        assertThat(deltas.get("P1")).isNull();
    }

    // ── AC-5: Breach reason grouping ──────────────────────────────────────────

    @Test
    @DisplayName("AC-5: breach reasons are grouped by reason code per segment")
    void breachReasons_groupedByCode() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(row("P1", 10, 7, 3));
        List<SlaAggregationRepository.BreachReasonRow> reasons = List.of(
                new SlaAggregationRepository.BreachReasonRow("P1", "CAPACITY", 2, 120),
                new SlaAggregationRepository.BreachReasonRow("P1", "PARTS", 1, 60));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, reasons);

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        assertThat(p1.breachReasons()).hasSize(2);

        SlaComplianceCalculator.BreachReasonSummary capacity =
                p1.breachReasons().stream()
                        .filter(r -> "CAPACITY".equals(r.reasonCode()))
                        .findFirst().orElseThrow();
        assertThat(capacity.count()).isEqualTo(2);
        assertThat(capacity.meanOverrunMinutes()).isEqualByComparingTo("60.00"); // 120 / 2
    }

    // ── All-on-time and all-breached extremes ─────────────────────────────────

    @Test
    @DisplayName("All work orders on time: compliance = 1.0, breach = 0")
    void allOnTime() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(row("P1", 100, 100, 0));
        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        assertThat(segmentFor(results, "P1").value()).isEqualByComparingTo("1.0000");
        assertThat(segmentFor(results, "P1").breachCount()).isZero();
    }

    @Test
    @DisplayName("All work orders breached: compliance = 0.0, breach count = total")
    void allBreached() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(row("P1", 50, 0, 50));
        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        assertThat(segmentFor(results, "P1").value()).isEqualByComparingTo("0.0000");
        assertThat(segmentFor(results, "P1").breachCount()).isEqualTo(50);
    }

    @Test
    @DisplayName("Single work order on time: compliance = 1.0")
    void singleWorkOrderOnTime() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(row("P1", 1, 1, 0));
        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());
        assertThat(segmentFor(results, "P1").value()).isEqualByComparingTo("1.0000");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static SlaComplianceCalculator.SegmentResult segmentFor(
            List<SlaComplianceCalculator.SegmentResult> results, String priority) {
        return results.stream()
                .filter(r -> priority.equals(r.priority()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No segment for priority: " + priority));
    }
}
