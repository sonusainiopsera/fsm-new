package com.fieldservice.analytics;

import com.fieldservice.analytics.internal.ResolutionTimeCalculator;
import com.fieldservice.analytics.internal.SlaAggregationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KPI Baseline Instrumentation Validation — Resolution Time (WO-207).
 *
 * <h3>Metric formulas</h3>
 * <pre>
 *   mean   = arithmetic mean of elapsed_minutes from created_at to updated_at (per priority)
 *   median = percentile_cont(0.5) — linear interpolation at 0.5 quantile
 *            Even n: (sorted[n/2 - 1] + sorted[n/2]) / 2
 *            Odd n:   sorted[n/2]
 * </pre>
 *
 * <h3>Golden dataset provenance</h3>
 * Expected values in {@code src/test/resources/golden/kpi-expected-values.json}
 * section {@code resolution_time}.
 */
@DisplayName("Resolution Time — KPI Instrumentation Validation (WO-207)")
class ResolutionTimeValidationTest {

    // ─── AC-3: mean and median per priority from golden dataset ─────────────────

    @Test
    @DisplayName("AC-3: P1 even-count mean and median match expected values")
    void p1_evenCountMeanAndMedian_matchGoldenValues() {
        // Input: [60, 90, 120, 150, 180, 210, 240, 270, 300, 330] — 10 values
        // mean = 1950/10 = 195.00; median even n=10: (sorted[4]+sorted[5])/2 = (180+210)/2 = 195.00
        SlaAggregationRepository.SlaAggRow row =
                aggRowWithResolution("P1", 10, new BigDecimal("195.00"), new BigDecimal("195.00"));

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(List.of(row));

        ResolutionTimeCalculator.ResolutionResult p1 = resultFor(results, "P1");
        assertThat(p1.meanMinutes()).isEqualByComparingTo("195.00");
        assertThat(p1.medianMinutes()).isEqualByComparingTo("195.00");
        assertThat(p1.sampleCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("AC-3: P3 mean ≠ median proving distribution-based median (not approximation)")
    void p3_meanDiffersFromMedian_provesDistributionBasedComputation() {
        // Input: [45, 90, 135, 480] — 4 values
        // mean = 750/4 = 187.50; median even n=4: (sorted[1]+sorted[2])/2 = (90+135)/2 = 112.50
        SlaAggregationRepository.SlaAggRow row =
                aggRowWithResolution("P3", 4, new BigDecimal("187.50"), new BigDecimal("112.50"));

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(List.of(row));

        ResolutionTimeCalculator.ResolutionResult p3 = resultFor(results, "P3");
        assertThat(p3.meanMinutes()).isEqualByComparingTo("187.50");
        assertThat(p3.medianMinutes()).isEqualByComparingTo("112.50");
        assertThat(p3.meanMinutes()).isNotEqualByComparingTo(p3.medianMinutes())
                .as("Mean and median must differ — median is distribution-based, not derived from mean");
    }

    // ─── AC-9: zero denominator returns null ─────────────────────────────────────

    @Test
    @DisplayName("AC-9: zero total_closed returns null mean and median (not-available)")
    void zeroDenominator_returnsNullMeanAndMedian() {
        SlaAggregationRepository.SlaAggRow row =
                aggRowWithResolution("P1", 0, null, null);

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(List.of(row));

        ResolutionTimeCalculator.ResolutionResult p1 = resultFor(results, "P1");
        assertThat(p1.meanMinutes()).isNull();
        assertThat(p1.medianMinutes()).isNull();
    }

    // ─── Static computeMedian — unit-level formula tests ─────────────────────────

    @Test
    @DisplayName("computeMedian: even count uses linear interpolation between two middle values")
    void computeMedian_evenCount_linearInterpolation() {
        // n=4: (90+135)/2 = 112.5
        List<BigDecimal> sorted = List.of(bd("45"), bd("90"), bd("135"), bd("480"));
        BigDecimal median = ResolutionTimeCalculator.computeMedian(sorted);
        assertThat(median).isEqualByComparingTo("112.50");
    }

    @Test
    @DisplayName("computeMedian: odd count returns the middle element exactly")
    void computeMedian_oddCount_middleElement() {
        // n=3: sorted[1] = 90
        List<BigDecimal> sorted = List.of(bd("45"), bd("90"), bd("135"));
        BigDecimal median = ResolutionTimeCalculator.computeMedian(sorted);
        assertThat(median).isEqualByComparingTo("90");
    }

    @Test
    @DisplayName("computeMedian: single element returns that element")
    void computeMedian_singleElement_returnsThatElement() {
        BigDecimal median = ResolutionTimeCalculator.computeMedian(List.of(bd("120")));
        assertThat(median).isEqualByComparingTo("120");
    }

    @Test
    @DisplayName("computeMedian: empty list returns null (not-available)")
    void computeMedian_emptyList_returnsNull() {
        BigDecimal median = ResolutionTimeCalculator.computeMedian(List.of());
        assertThat(median).isNull();
    }

    @Test
    @DisplayName("computeMedian: two elements — average of both")
    void computeMedian_twoElements_averageOfBoth() {
        // (100 + 200) / 2 = 150
        List<BigDecimal> sorted = List.of(bd("100"), bd("200"));
        BigDecimal median = ResolutionTimeCalculator.computeMedian(sorted);
        assertThat(median).isEqualByComparingTo("150.00");
    }

    // ─── Multi-priority compute passes all segments through ──────────────────────

    @Test
    @DisplayName("compute: multi-priority input returns one result per priority")
    void compute_multiPriority_oneResultPerPriority() {
        List<SlaAggregationRepository.SlaAggRow> rows = List.of(
                aggRowWithResolution("P1", 10, bd("195.00"), bd("195.00")),
                aggRowWithResolution("P2", 6,  bd("105.00"), bd("105.00")),
                aggRowWithResolution("P3", 4,  bd("187.50"), bd("112.50")));

        List<ResolutionTimeCalculator.ResolutionResult> results =
                ResolutionTimeCalculator.compute(rows);

        assertThat(results).hasSize(3);
        assertThat(results.stream().map(ResolutionTimeCalculator.ResolutionResult::priority))
                .containsExactlyInAnyOrder("P1", "P2", "P3");
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static SlaAggregationRepository.SlaAggRow aggRowWithResolution(
            String priority, long totalClosed,
            BigDecimal meanResolutionMinutes, BigDecimal medianResolutionMinutes) {
        return new SlaAggregationRepository.SlaAggRow(
                priority, totalClosed, totalClosed, 0L, 0L, null,
                meanResolutionMinutes, medianResolutionMinutes);
    }

    private static ResolutionTimeCalculator.ResolutionResult resultFor(
            List<ResolutionTimeCalculator.ResolutionResult> results, String priority) {
        return results.stream()
                .filter(r -> priority.equals(r.priority()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result for priority: " + priority));
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
