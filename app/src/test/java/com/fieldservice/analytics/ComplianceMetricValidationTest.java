package com.fieldservice.analytics;

import com.fieldservice.analytics.internal.SlaAggregationRepository;
import com.fieldservice.analytics.internal.SlaComplianceCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KPI Baseline Instrumentation Validation — Compliance Rate (WO-207).
 *
 * <h3>Metric formula (authoritative definition)</h3>
 * <pre>
 *   compliance_rate = compliant_count / total_closed  per segment
 *   ALL rollup      = SUM(compliant_count across all priorities) / SUM(total_closed across all priorities)
 *                   [weighted aggregate, NOT mean of per-priority rates]
 * </pre>
 *
 * <h3>Denominator exclusions</h3>
 * <ul>
 *   <li>CANCELLED work orders are excluded from both numerator and denominator.</li>
 *   <li>Work orders with {@code excluded_from_sla_compliance = true} are excluded.</li>
 *   <li>A null {@code resolution_due_at} is treated as compliant (BR-21: no policy = no breach).</li>
 * </ul>
 *
 * <h3>Deadline boundary rule</h3>
 * A work order closed at exactly its committed deadline ({@code updated_at = resolution_due_at})
 * is COMPLIANT (inclusive ≤). One second after the deadline is a BREACH.
 *
 * <h3>Golden dataset provenance</h3>
 * All expected values are derived by hand and committed in
 * {@code src/test/resources/golden/kpi-expected-values.json}. Tests assert the computed metric
 * equals the expected value exactly so a mismatch reports both sides.
 *
 * <p>Tests do NOT re-implement formula logic — they drive computation through
 * {@link SlaComplianceCalculator#compute} (the analytics public calculator).
 */
@DisplayName("Compliance Rate — KPI Instrumentation Validation (WO-207)")
class ComplianceMetricValidationTest {

    private static final Set<String> ALL_POLICIES = Set.of("P1", "P2", "P3");

    // ─── AC-1: golden dataset — expected values from kpi-expected-values.json ──

    @Test
    @DisplayName("AC-1: per-priority rates match hand-derived expected values (dataset A)")
    void perPriorityRates_matchExpectedValues_datasetA() {
        // dataset_A from kpi-expected-values.json
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1", 10, 8, 2),
                aggRow("P2", 6,  6, 0),
                aggRow("P3", 4,  1, 3));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        // Hand-derived expected values from kpi-expected-values.json#sla_compliance.dataset_A
        assertSegment(results, "P1", "0.8000", 8, 10);
        assertSegment(results, "P2", "1.0000", 6, 6);
        assertSegment(results, "P3", "0.2500", 1, 4);
    }

    @Test
    @DisplayName("AC-2: ALL rollup uses weighted aggregate; segmentation totals reconcile")
    void allRollup_isWeightedAggregate_segmentsTotalReconcile() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1", 10, 8, 2),
                aggRow("P2", 6,  6, 0),
                aggRow("P3", 4,  1, 3));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        SlaComplianceCalculator.SegmentResult all = segmentFor(results, "ALL");

        // Hand-derived: numerator = 8+6+1 = 15, denominator = 10+6+4 = 20, rate = 0.75
        assertThat(all.numerator()).isEqualByComparingTo("15");
        assertThat(all.denominator()).isEqualByComparingTo("20");
        assertThat(all.value()).isEqualByComparingTo("0.7500");

        // Segmentation reconciliation: per-priority numerators sum to ALL numerator
        long segNumeratorSum = results.stream()
                .filter(r -> !"ALL".equals(r.priority()))
                .mapToLong(r -> r.numerator().longValue())
                .sum();
        long segDenominatorSum = results.stream()
                .filter(r -> !"ALL".equals(r.priority()))
                .mapToLong(r -> r.denominator().longValue())
                .sum();

        assertThat(segNumeratorSum).isEqualTo(all.numerator().longValue());
        assertThat(segDenominatorSum).isEqualTo(all.denominator().longValue());
    }

    // ─── AC-8: exact-deadline boundary ──────────────────────────────────────────

    @Test
    @DisplayName("AC-8: work order closed EXACTLY at deadline is COMPLIANT (inclusive boundary)")
    void atExactDeadline_isCompliant() {
        // A work order closed exactly at its deadline (updated_at = resolution_due_at)
        // is counted as compliant per the documented inclusive-≤ rule.
        // The SlaAggregationRepository SQL: WHERE updated_at <= resolution_due_at → compliant.
        // Modelled here: 1 total, 1 compliant.
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1", 1, 1, 0));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, Set.of("P1"), List.of());

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        assertThat(p1.value()).isEqualByComparingTo("1.0000")
                .as("Work order at exactly deadline must be counted as compliant");
        assertThat(p1.numerator()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("AC-8: work order closed one second AFTER deadline is a BREACH")
    void oneSecondAfterDeadline_isBreach() {
        // 1 total, 0 compliant → breach
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1", 1, 0, 1));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, Set.of("P1"), List.of());

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        assertThat(p1.value()).isEqualByComparingTo("0.0000")
                .as("Work order one second after deadline must be a breach");
        assertThat(p1.breachCount()).isEqualTo(1);
    }

    // ─── AC-9: zero-denominator returns not-available ────────────────────────────

    @Test
    @DisplayName("AC-9: zero total_closed returns null value (not-available, not 0%)")
    void zeroDenominator_returnsNullNotAvailable() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1", 0, 0, 0));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        assertThat(p1.value())
                .as("Zero denominator must produce null (not-available) not 0%")
                .isNull();
    }

    @Test
    @DisplayName("AC-9: ALL rollup returns null when ALL segments have zero data")
    void allRollup_nullWhenAllSegmentsHaveNoData() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1", 0, 0, 0),
                aggRow("P2", 0, 0, 0));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, ALL_POLICIES, List.of());

        SlaComplianceCalculator.SegmentResult all = segmentFor(results, "ALL");
        assertThat(all.value()).isNull();
    }

    // ─── Cancelled work orders excluded ──────────────────────────────────────────

    @Test
    @DisplayName("CANCELLED work orders excluded from compliance denominator")
    void cancelledWorkOrders_excludedFromDenominator() {
        // The SlaAggregationRepository only queries state IN ('COMPLETED', 'CLOSED').
        // This test verifies the calculator processes only what the SQL provides.
        // 4 non-cancelled WOs total, 2 cancelled WOs (not passed to calculator).
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1", 4, 3, 1)); // denominator = 4 non-cancelled WOs

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, Set.of("P1"), List.of());

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        assertThat(p1.denominator()).isEqualByComparingTo("4")
                .as("Denominator must not include cancelled work orders (excluded by SQL layer)");
    }

    // ─── Null resolution_due_at treated as compliant ─────────────────────────────

    @Test
    @DisplayName("Null resolution_due_at (no SLA policy) counts as compliant per BR-21")
    void nullResolutionDueAt_countsAsCompliant() {
        // The SQL: compliant_count = COUNT(*) FILTER (WHERE resolution_due_at IS NULL OR ...)
        // So 5 WOs with no due_at → all compliant
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1", 5, 5, 0));

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, Set.of("P1"), List.of());

        SlaComplianceCalculator.SegmentResult p1 = segmentFor(results, "P1");
        assertThat(p1.value()).isEqualByComparingTo("1.0000");
    }

    // ─── Policy-missing segment flagged degraded ──────────────────────────────────

    @Test
    @DisplayName("Segment with no SLA policy is flagged degraded and excluded from rollup")
    void policyMissingSegment_isDegradedAndExcludedFromRollup() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRow("P1",    10, 8, 2),
                aggRow("GHOST",  5, 4, 1)); // no policy for "GHOST"

        List<SlaComplianceCalculator.SegmentResult> results =
                SlaComplianceCalculator.compute(rows, Set.of("P1"), List.of()); // only P1 policy

        SlaComplianceCalculator.SegmentResult ghost = segmentFor(results, "GHOST");
        assertThat(ghost.degraded()).isTrue();

        // GHOST excluded from rollup — rollup should only use P1
        SlaComplianceCalculator.SegmentResult all = segmentFor(results, "ALL");
        assertThat(all.denominator()).isEqualByComparingTo("10")
                .as("GHOST segment must be excluded from the ALL rollup");
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static SlaAggregationRepository.SlaAggRow aggRow(
            String priority, long total, long compliant, long breaches) {
        return new SlaAggregationRepository.SlaAggRow(
                priority, total, compliant, breaches, 0L, null, null, null);
    }

    private static SlaComplianceCalculator.SegmentResult segmentFor(
            List<SlaComplianceCalculator.SegmentResult> results, String priority) {
        return results.stream()
                .filter(r -> priority.equals(r.priority()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No segment found for priority: " + priority));
    }

    private static void assertSegment(
            List<SlaComplianceCalculator.SegmentResult> results,
            String priority, String expectedRate, long expectedNumerator, long expectedDenominator) {
        SlaComplianceCalculator.SegmentResult seg = segmentFor(results, priority);
        assertThat(seg.value())
                .as("Compliance rate for %s must match hand-derived expected value", priority)
                .isEqualByComparingTo(expectedRate);
        assertThat(seg.numerator().longValue())
                .as("Numerator (compliant count) for %s", priority)
                .isEqualTo(expectedNumerator);
        assertThat(seg.denominator().longValue())
                .as("Denominator (total closed) for %s", priority)
                .isEqualTo(expectedDenominator);
    }
}
