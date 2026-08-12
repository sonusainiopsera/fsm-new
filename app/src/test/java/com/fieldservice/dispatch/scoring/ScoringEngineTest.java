package com.fieldservice.dispatch.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Unit tests for ScoringEngine, all four factor implementations, and the
 * workload fairness penalty curve — no Spring context required.
 */
class ScoringEngineTest {

    // ── Shared fixtures ─────────────────────────────────────────────────────

    static final UUID TECH_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID TECH_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID TECH_C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    static final double EXPONENT = 1.5;
    static final int    HORIZON  = 90;

    static ScoringContext ctx(double teamMean) {
        return new ScoringContext(List.of("ELEC_DISPATCH"), "BOILER_FAULT", teamMean, HORIZON);
    }

    static CandidateScoringData data(UUID id, double bookedHours, int travelMinutes,
                                     int experience, List<String> certs) {
        return new CandidateScoringData(
                id, certs, experience, bookedHours,
                new TravelTimeResult(id, travelMinutes, false), 1.0);
    }

    static ScoringWeights weights(double competency, double travel,
                                   double workload, double parts) {
        return new ScoringWeights(Map.of(
                CompetencyFitFactor.FACTOR_CODE,     competency,
                TravelEfficiencyFactor.FACTOR_CODE,  travel,
                WorkloadFairnessFactor.FACTOR_CODE,  workload,
                PartsAvailabilityFactor.FACTOR_CODE, parts),
                EXPONENT, HORIZON);
    }

    ScoringEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ScoringEngine(List.of(
                new CompetencyFitFactor(),
                new TravelEfficiencyFactor(),
                new WorkloadFairnessFactor(EXPONENT),
                new PartsAvailabilityFactor()));
    }

    // ── AC-1: Ordering ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC-1: Ordering and tie-break")
    class OrderingTests {

        @Test
        @DisplayName("Candidates ordered by descending composite score")
        void ordersByDescendingScore() {
            ScoringWeights w = weights(0.35, 0.30, 0.25, 0.10);
            // TECH_A: 15 min travel, 5 experience, 4h booked
            // TECH_B: 80 min travel, 0 experience, 7h booked (worse on all factors)
            var candidates = List.of(
                    data(TECH_B, 7, 80, 0, List.of("ELEC_DISPATCH")),
                    data(TECH_A, 4, 15, 5, List.of("ELEC_DISPATCH")));

            List<ScoredCandidate> results = engine.score(candidates, ctx(5.5), w);
            assertThat(results.get(0).technicianId()).isEqualTo(TECH_A);
            assertThat(results.get(1).technicianId()).isEqualTo(TECH_B);
            assertThat(results.get(0).compositeScore()).isGreaterThan(results.get(1).compositeScore());
        }

        @Test
        @DisplayName("Identical scores tie-broken ascending by technician identifier")
        void tieBreakByIdentifier() {
            ScoringWeights w = weights(0, 0, 0, 0); // all zero → same score 0.0
            var candidates = List.of(
                    data(TECH_C, 5, 30, 3, List.of()),
                    data(TECH_A, 5, 30, 3, List.of()),
                    data(TECH_B, 5, 30, 3, List.of()));

            List<ScoredCandidate> results = engine.score(candidates, ctx(5.0), w);
            assertThat(results).extracting(ScoredCandidate::technicianId)
                    .containsExactly(TECH_A, TECH_B, TECH_C);
        }

        @Test
        @DisplayName("Repeating identical input produces byte-identical ordering")
        void determinism() {
            ScoringWeights w = weights(0.35, 0.30, 0.25, 0.10);
            var candidates = List.of(
                    data(TECH_A, 4, 20, 10, List.of("ELEC_DISPATCH")),
                    data(TECH_B, 6, 45,  3, List.of("ELEC_DISPATCH")),
                    data(TECH_C, 8, 70,  1, List.of()));

            List<ScoredCandidate> first  = engine.score(candidates, ctx(6.0), w);
            List<ScoredCandidate> second = engine.score(candidates, ctx(6.0), w);

            assertThat(first).extracting(ScoredCandidate::technicianId)
                    .isEqualTo(second.stream().map(ScoredCandidate::technicianId).toList());
            for (int i = 0; i < first.size(); i++) {
                assertThat(first.get(i).compositeScore())
                        .isEqualTo(second.get(i).compositeScore());
            }
        }
    }

    // ── AC-6: Score in [0,1] ─────────────────────────────────────────────────

    @Nested
    @DisplayName("AC-6: Composite score always in [0,1]")
    class ScoreRangeTests {

        @Test
        void scoreAlwaysInRange() {
            ScoringWeights w = weights(0.35, 0.30, 0.25, 0.10);
            var candidates = List.of(
                    data(TECH_A, 0,  0, 20, List.of("ELEC_DISPATCH")),
                    data(TECH_B, 50, 90, 0, List.of()),
                    data(TECH_C, 7, 45,  5, List.of("ELEC_DISPATCH")));

            engine.score(candidates, ctx(20.0), w).forEach(sc ->
                    assertThat(sc.compositeScore()).isBetween(0.0, 1.0));
        }

        @Test
        void allWeightsZeroGivesNeutralScore() {
            ScoringWeights w = weights(0, 0, 0, 0);
            var candidates = List.of(data(TECH_A, 5, 30, 5, List.of("ELEC_DISPATCH")));
            List<ScoredCandidate> result = engine.score(candidates, ctx(5.0), w);
            assertThat(result.get(0).compositeScore()).isEqualTo(0.0);
        }
    }

    // ── AC-4: Workload fairness penalty ──────────────────────────────────────

    @Nested
    @DisplayName("AC-4: Workload fairness — super-linear penalty")
    class WorkloadFairnessTests {

        final WorkloadFairnessFactor factor = new WorkloadFairnessFactor(EXPONENT);

        @Test
        @DisplayName("Technician at team mean receives no penalty (normalised=1.0)")
        void atMeanNoPenalty() {
            var data = data(TECH_A, 7, 30, 5, List.of());
            var ctx  = ctx(7.0);
            FactorBreakdown bd = factor.normalise(data, ctx);
            assertThat(bd.normalisedValue()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Technician below team mean receives no penalty (normalised=1.0)")
        void belowMeanNoPenalty() {
            var data = data(TECH_A, 3, 30, 5, List.of());
            var ctx  = ctx(7.0);
            FactorBreakdown bd = factor.normalise(data, ctx);
            assertThat(bd.normalisedValue()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("AC-4: 9h booked ranks below 6h booked against team mean 7h")
        void nineHoursRanksBelowSixHours() {
            ScoringWeights w = new ScoringWeights(
                    Map.of(WorkloadFairnessFactor.FACTOR_CODE, 1.0),
                    EXPONENT, HORIZON);
            ScoringEngine workloadOnly = new ScoringEngine(List.of(factor));

            var tech6 = new CandidateScoringData(TECH_A, List.of(), 0, 6.0,
                    TravelTimeResult.degraded(TECH_A), 1.0);
            var tech9 = new CandidateScoringData(TECH_B, List.of(), 0, 9.0,
                    TravelTimeResult.degraded(TECH_B), 1.0);

            ScoringContext ctx = new ScoringContext(List.of(), null, 7.0, HORIZON);
            List<ScoredCandidate> results = workloadOnly.score(List.of(tech9, tech6), ctx, w);

            assertThat(results.get(0).technicianId()).isEqualTo(TECH_A); // 6h first
            assertThat(results.get(0).compositeScore())
                    .isGreaterThan(results.get(1).compositeScore());
        }

        @Test
        @DisplayName("Zero team mean returns neutral score (avoids division by zero)")
        void zeroTeamMeanNeutral() {
            var data = data(TECH_A, 0, 30, 5, List.of());
            var ctx  = ctx(0.0);
            FactorBreakdown bd = factor.normalise(data, ctx);
            assertThat(bd.normalisedValue()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Exponent <= 1.0 is rejected at construction")
        void exponentMustBeGreaterThanOne() {
            assertThatThrownBy(() -> new WorkloadFairnessFactor(1.0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new WorkloadFairnessFactor(0.5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── AC-2: Factor breakdown fields ────────────────────────────────────────

    @Nested
    @DisplayName("AC-2: Every factor produces a full breakdown")
    class BreakdownTests {

        @Test
        void breakdownContainsAllRequiredFields() {
            ScoringWeights w = weights(0.35, 0.30, 0.25, 0.10);
            var candidates = List.of(data(TECH_A, 4, 20, 8, List.of("ELEC_DISPATCH")));
            ScoredCandidate sc = engine.score(candidates, ctx(4.0), w).get(0);

            assertThat(sc.breakdown()).hasSize(4);
            sc.breakdown().forEach(bd -> {
                assertThat(bd.factorCode()).isNotBlank();
                assertThat(bd.normalisedValue()).isBetween(0.0, 1.0);
                assertThat(bd.weight()).isGreaterThanOrEqualTo(0.0);
                assertThat(bd.weightedContribution()).isGreaterThanOrEqualTo(0.0);
                assertThat(bd.explanation()).isNotBlank();
            });
        }
    }

    // ── AC-3: Degraded travel ────────────────────────────────────────────────

    @Nested
    @DisplayName("AC-3: Degraded travel estimate")
    class DegradedTravelTests {

        @Test
        @DisplayName("Degraded travel contributes neutral 0.5 and sets degraded flag")
        void degradedTravelNeutralContribution() {
            var candidateData = new CandidateScoringData(
                    TECH_A, List.of("ELEC_DISPATCH"), 5, 4.0,
                    TravelTimeResult.degraded(TECH_A), 1.0);

            ScoringWeights w = weights(0, 1.0, 0, 0); // only travel
            ScoredCandidate sc = engine.score(List.of(candidateData), ctx(4.0), w).get(0);

            assertThat(sc.degraded()).isTrue();
            FactorBreakdown travel = sc.breakdown().stream()
                    .filter(b -> TravelEfficiencyFactor.FACTOR_CODE.equals(b.factorCode()))
                    .findFirst().orElseThrow();
            assertThat(travel.normalisedValue()).isEqualTo(TravelEfficiencyFactor.NEUTRAL_DEGRADED);
            assertThat(travel.degraded()).isTrue();
        }
    }

    // ── CompetencyFitFactor boundaries ───────────────────────────────────────

    @Nested
    @DisplayName("CompetencyFitFactor boundaries")
    class CompetencyFitTests {

        final CompetencyFitFactor factor = new CompetencyFitFactor();

        @Test
        void fullCertsAndMaxExperienceScoresOne() {
            var data = data(TECH_A, 0, 0, CompetencyFitFactor.EXPERIENCE_CAP,
                    List.of("ELEC_DISPATCH"));
            FactorBreakdown bd = factor.normalise(data, ctx(5.0));
            assertThat(bd.normalisedValue()).isEqualTo(1.0, offset(0.001));
        }

        @Test
        void missingCertScoresLessThanFull() {
            var data = data(TECH_A, 0, 0, 10, List.of()); // no cert
            FactorBreakdown bd = factor.normalise(data, ctx(5.0));
            assertThat(bd.normalisedValue()).isLessThan(1.0);
        }

        @Test
        void noCertsRequiredScoresCertPartOne() {
            var dataExperienced = data(TECH_A, 0, 0, CompetencyFitFactor.EXPERIENCE_CAP, List.of());
            ScoringContext noReqs = new ScoringContext(List.of(), null, 5.0, HORIZON);
            FactorBreakdown bd = factor.normalise(dataExperienced, noReqs);
            // cert portion = 1.0 (none required), experience at cap = 1.0 → total 1.0
            assertThat(bd.normalisedValue()).isEqualTo(1.0, offset(0.001));
        }

        @Test
        void experienceBeyondCapClampedToOne() {
            var data = data(TECH_A, 0, 0, 999, List.of("ELEC_DISPATCH"));
            FactorBreakdown bd = factor.normalise(data, ctx(5.0));
            assertThat(bd.normalisedValue()).isLessThanOrEqualTo(1.0);
        }
    }

    // ── TravelEfficiencyFactor boundaries ─────────────────────────────────────

    @Nested
    @DisplayName("TravelEfficiencyFactor boundaries")
    class TravelEfficiencyTests {

        final TravelEfficiencyFactor factor = new TravelEfficiencyFactor();

        @Test
        void zeroMinutesScoresOne() {
            var data = data(TECH_A, 0, 0, 0, List.of());
            assertThat(factor.normalise(data, ctx(5.0)).normalisedValue()).isEqualTo(1.0);
        }

        @Test
        void atHorizonScoresZero() {
            var data = data(TECH_A, 0, HORIZON, 0, List.of());
            assertThat(factor.normalise(data, ctx(5.0)).normalisedValue()).isEqualTo(0.0, offset(0.001));
        }

        @Test
        void beyondHorizonClampedToZero() {
            var data = data(TECH_A, 0, HORIZON + 30, 0, List.of());
            assertThat(factor.normalise(data, ctx(5.0)).normalisedValue()).isEqualTo(0.0);
        }
    }

    // ── AC-8: Performance budget ──────────────────────────────────────────────

    @Test
    @DisplayName("AC-8: Scoring 200 candidates within 150 ms (generous CI multiplier)")
    void scoringBudget200Candidates() {
        ScoringWeights w = weights(0.35, 0.30, 0.25, 0.10);
        List<CandidateScoringData> candidates = new java.util.ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            UUID id = UUID.randomUUID();
            candidates.add(new CandidateScoringData(
                    id, List.of("ELEC_DISPATCH"), i % 20, i * 0.1,
                    new TravelTimeResult(id, i % 90, false), 1.0));
        }
        ScoringContext ctx = new ScoringContext(List.of("ELEC_DISPATCH"), "BOILER", 10.0, HORIZON);

        long start = System.nanoTime();
        List<ScoredCandidate> results = engine.score(candidates, ctx, w);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(results).hasSize(200);
        assertThat(elapsedMs)
                .as("Scoring 200 candidates should complete within 150 ms (was %d ms)", elapsedMs)
                .isLessThan(150);
    }
}
