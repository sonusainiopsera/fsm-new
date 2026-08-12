package com.fieldservice.analytics.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResolutionTimeCalculator} (WO-162, AC-3/4/6/7).
 *
 * Pure in-memory tests — mirrors PostgreSQL percentile_cont(0.5) semantics for median.
 */
@DisplayName("ResolutionTimeCalculator unit tests")
class ResolutionTimeCalculatorTest {

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static SlaAggregationRepository.SlaAggRow row(
            String priority, long total, BigDecimal mean, BigDecimal median) {
        // SlaAggRow(priority, totalClosed, compliantCount, breachCount, totalOverrunMinutes,
        //           meanOverrunMinutes, meanResolutionMinutes, medianResolutionMinutes)
        return new SlaAggregationRepository.SlaAggRow(
                priority, total, 0, 0, 0, null, mean, median);
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    // ── AC-3: Mean ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-3: mean carried through from aggregation row")
    void mean_carriedThrough() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                row("P1", 10, bd("120.5"), bd("100.0")));

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(rows);

        assertThat(segmentFor(results, "P1").meanMinutes()).isEqualByComparingTo("120.50");
    }

    @Test
    @DisplayName("AC-3: ALL rollup mean is weighted (not simple average of per-priority means)")
    void rollupMean_isWeighted() {
        // P1: mean=120 over 10 tickets; P2: mean=60 over 2 tickets
        // Weighted: (120*10 + 60*2) / 12 = 1320/12 = 110.00
        // Simple mean: (120+60)/2 = 90 — differs
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                row("P1", 10, bd("120.00"), bd("115.00")),
                row("P2",  2, bd("60.00"),  bd("58.00")));

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(rows);

        assertThat(segmentFor(results, "ALL").meanMinutes()).isEqualByComparingTo("110.00");
    }

    // ── AC-4: Median parity with PostgreSQL percentile_cont(0.5) ──────────────

    @Test
    @DisplayName("AC-4: computeMedian — odd count returns middle element")
    void computeMedian_oddCount() {
        // n=3: sorted = [1, 2, 3] → median = sorted[1] = 2
        List<BigDecimal> values = List.of(bd("1"), bd("2"), bd("3"));
        assertThat(ResolutionTimeCalculator.computeMedian(values)).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("AC-4: computeMedian — even count interpolates two middle values")
    void computeMedian_evenCount() {
        // n=4: sorted = [1, 2, 3, 4] → median = (sorted[1] + sorted[2]) / 2 = (2+3)/2 = 2.5
        List<BigDecimal> values = List.of(bd("1"), bd("2"), bd("3"), bd("4"));
        assertThat(ResolutionTimeCalculator.computeMedian(values)).isEqualByComparingTo("2.50");
    }

    @Test
    @DisplayName("AC-4: computeMedian — single value is itself")
    void computeMedian_singleValue() {
        assertThat(ResolutionTimeCalculator.computeMedian(List.of(bd("42"))))
                .isEqualByComparingTo("42.00");
    }

    @Test
    @DisplayName("AC-4: computeMedian — empty list returns null")
    void computeMedian_emptyList_returnsNull() {
        assertThat(ResolutionTimeCalculator.computeMedian(List.of())).isNull();
    }

    @Test
    @DisplayName("AC-4: computeMedian — two equal values returns same value")
    void computeMedian_twoEqualValues() {
        List<BigDecimal> values = List.of(bd("50"), bd("50"));
        assertThat(ResolutionTimeCalculator.computeMedian(values)).isEqualByComparingTo("50.00");
    }

    // ── AC-6: No-data semantics ────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6: zero total_closed returns null mean and null median (not 0)")
    void zeroClosed_returnsNullValues() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                row("P1", 0, null, null));

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(rows);

        ResolutionTimeCalculator.ResolutionResult p1 = segmentFor(results, "P1");
        assertThat(p1.meanMinutes()).isNull();
        assertThat(p1.medianMinutes()).isNull();
        assertThat(p1.sampleCount()).isZero();
    }

    @Test
    @DisplayName("AC-6: empty rows produces ALL rollup with null mean and zero sample count")
    void emptyRows_allRollupHasNullMean() {
        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(List.of());

        assertThat(results).hasSize(1);
        ResolutionTimeCalculator.ResolutionResult all = results.get(0);
        assertThat(all.priority()).isEqualTo("ALL");
        assertThat(all.meanMinutes()).isNull();
        assertThat(all.sampleCount()).isZero();
    }

    // ── AC-7: Rollup median is null (requires separate percentile SQL) ─────────

    @Test
    @DisplayName("AC-7: ALL rollup median is null (cannot be derived from per-priority percentiles)")
    void allRollupMedian_isNull() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                row("P1", 10, bd("120.00"), bd("115.00")),
                row("P2",  5, bd("60.00"),  bd("55.00")));

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(rows);

        assertThat(segmentFor(results, "ALL").medianMinutes()).isNull();
    }

    // ── Sample count propagation ───────────────────────────────────────────────

    @Test
    @DisplayName("sampleCount propagated per segment and summed for rollup")
    void sampleCount_propagated() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                row("P1", 8, bd("100"), bd("95")),
                row("P2", 4, bd("200"), bd("190")));

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(rows);

        assertThat(segmentFor(results, "P1").sampleCount()).isEqualTo(8);
        assertThat(segmentFor(results, "P2").sampleCount()).isEqualTo(4);
        assertThat(segmentFor(results, "ALL").sampleCount()).isEqualTo(12);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static ResolutionTimeCalculator.ResolutionResult segmentFor(
            List<ResolutionTimeCalculator.ResolutionResult> results, String priority) {
        return results.stream()
                .filter(r -> priority.equals(r.priority()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No segment for priority: " + priority));
    }
}
