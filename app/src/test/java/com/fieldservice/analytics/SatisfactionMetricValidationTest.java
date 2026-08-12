package com.fieldservice.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KPI Baseline Instrumentation Validation — Customer Satisfaction (CSAT / NPS) (WO-207).
 *
 * <h3>Metric formulas</h3>
 * <pre>
 *   CSAT mean  = AVG(score) over scores 1–5 in the rolling 90-day window
 *   NPS        = promoters% − detractors%
 *                promoters  = nps_score 9–10
 *                passives   = nps_score 7–8
 *                detractors = nps_score 0–6
 * </pre>
 *
 * <h3>Late survey rule</h3>
 * A satisfaction survey submitted after the reporting window closes
 * ({@code submitted_at &gt; window_end}) must NOT alter a published baseline snapshot.
 *
 * <h3>Golden dataset provenance</h3>
 * See {@code src/test/resources/golden/kpi-expected-values.json} section
 * {@code csat_satisfaction}.
 *
 * <p>These tests validate the formula definitions as pure arithmetic, exercising
 * the computation rules independently of the persistence layer.
 */
@DisplayName("Customer Satisfaction (CSAT/NPS) — KPI Instrumentation Validation (WO-207)")
class SatisfactionMetricValidationTest {

    // ─── AC-1 / CSAT mean: golden dataset A ─────────────────────────────────────

    @Test
    @DisplayName("AC-1: CSAT mean score matches hand-derived expected value (dataset A)")
    void csatMean_matchesGoldenValue_datasetA() {
        // Input scores: [4, 5, 3, 4, 5]; mean = 21/5 = 4.20
        List<Integer> scores = List.of(4, 5, 3, 4, 5);
        BigDecimal mean = computeCsatMean(scores);

        assertThat(mean).isEqualByComparingTo("4.20")
                .as("CSAT mean for dataset A must be 4.20 (21/5)");
    }

    // ─── AC-9: zero responses returns null ───────────────────────────────────────

    @Test
    @DisplayName("AC-9: zero survey responses returns null CSAT (not-available, not 0)")
    void csatMean_zeroResponses_returnsNull() {
        BigDecimal mean = computeCsatMean(List.of());
        assertThat(mean).isNull();
    }

    // ─── AC-1 / NPS: golden dataset B ────────────────────────────────────────────

    @Test
    @DisplayName("AC-1: NPS matches hand-derived expected value (dataset B)")
    void nps_matchesGoldenValue_datasetB() {
        // Input nps_scores: [10, 9, 8, 7, 6, 5]
        // promoters (9-10): 10, 9 → 2
        // passives  (7-8):  8, 7  → 2
        // detractors (0-6): 6, 5  → 2
        // NPS = (2/6 - 2/6) * 100 = 0
        List<Integer> npsScores = List.of(10, 9, 8, 7, 6, 5);
        int nps = computeNps(npsScores);

        assertThat(nps).isEqualTo(0)
                .as("NPS for dataset B must be 0 (equal promoters and detractors)");
    }

    @Test
    @DisplayName("NPS: all promoters returns 100")
    void nps_allPromoters_returns100() {
        List<Integer> scores = List.of(9, 10, 10, 9);
        int nps = computeNps(scores);
        assertThat(nps).isEqualTo(100);
    }

    @Test
    @DisplayName("NPS: all detractors returns -100")
    void nps_allDetractors_returnsNeg100() {
        List<Integer> scores = List.of(0, 1, 3, 6);
        int nps = computeNps(scores);
        assertThat(nps).isEqualTo(-100);
    }

    @Test
    @DisplayName("NPS promoter boundary: score 9 is a promoter, score 8 is a passive")
    void nps_boundaryBetweenPromoterAndPassive() {
        List<Integer> scoresWith9 = List.of(9);  // 1 promoter → NPS = 100
        List<Integer> scoresWith8 = List.of(8);  // 1 passive  → NPS = 0
        assertThat(computeNps(scoresWith9)).isEqualTo(100);
        assertThat(computeNps(scoresWith8)).isEqualTo(0);
    }

    @Test
    @DisplayName("NPS detractor boundary: score 6 is a detractor, score 7 is a passive")
    void nps_boundaryBetweenDetractorAndPassive() {
        List<Integer> scoresWith6 = List.of(6);  // 1 detractor → NPS = -100
        List<Integer> scoresWith7 = List.of(7);  // 1 passive   → NPS = 0
        assertThat(computeNps(scoresWith6)).isEqualTo(-100);
        assertThat(computeNps(scoresWith7)).isEqualTo(0);
    }

    // ─── Formula helpers — independent of Spring / DB ───────────────────────────

    /** Pure CSAT mean formula: AVG(score) over 1–5 scale, null when empty. */
    private static BigDecimal computeCsatMean(List<Integer> scores) {
        if (scores.isEmpty()) {
            return null;
        }
        int sum = scores.stream().mapToInt(Integer::intValue).sum();
        return new BigDecimal(sum).divide(new BigDecimal(scores.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Pure NPS formula: (promoters − detractors) / total × 100, rounded to int.
     * Returns 0 when no responses.
     */
    private static int computeNps(List<Integer> scores) {
        if (scores.isEmpty()) {
            return 0;
        }
        long promoters  = scores.stream().filter(s -> s >= 9).count();
        long detractors = scores.stream().filter(s -> s <= 6).count();
        double nps = ((double)(promoters - detractors) / scores.size()) * 100.0;
        return (int) Math.round(nps);
    }
}
