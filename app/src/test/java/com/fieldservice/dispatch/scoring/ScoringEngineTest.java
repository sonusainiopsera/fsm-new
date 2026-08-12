package com.fieldservice.dispatch.scoring;

import com.fieldservice.dispatch.scoring.factors.CompetencyFitFactor;
import com.fieldservice.dispatch.scoring.factors.PartsAvailabilityFactor;
import com.fieldservice.dispatch.scoring.factors.TravelEfficiencyFactor;
import com.fieldservice.dispatch.scoring.factors.WorkloadFairnessFactor;
import com.fieldservice.inventory.api.PartsAvailabilityStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for ScoringEngine — no Spring context required.
 *
 * <p>Covers:
 * <ul>
 *   <li>Per-factor normalisation boundaries (0, mid, 1)</li>
 *   <li>Workload penalty curve (AC-4)</li>
 *   <li>Determinism: repeated calls produce identical scores</li>
 *   <li>Tie-break ordering (AC-1)</li>
 *   <li>Zero-weight factor deactivation (AC-6)</li>
 *   <li>Degraded input handling</li>
 *   <li>200-candidate timing budget (AC-8)</li>
 *   <li>Score always in [0,1] (AC-6)</li>
 * </ul>
 */
class ScoringEngineTest {

    private static final ScoringWeights DEFAULT_WEIGHTS = new ScoringWeights(List.of(
            new ScoringWeights.WeightEntry(CompetencyFitFactor.CODE,    1.0, true),
            new ScoringWeights.WeightEntry(TravelEfficiencyFactor.CODE, 1.0, true),
            new ScoringWeights.WeightEntry(WorkloadFairnessFactor.CODE, 1.0, true),
            new ScoringWeights.WeightEntry(PartsAvailabilityFactor.CODE, 1.0, true)
    ), 2.0);

    private ScoringEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ScoringEngine(List.of(
                new CompetencyFitFactor(),
                new TravelEfficiencyFactor(),
                new WorkloadFairnessFactor(),
                new PartsAvailabilityFactor()
        ));
    }

    // ── CompetencyFitFactor boundary tests ──────────────────────────────────

    @Test
    void competency_fullCoverage_highExperience_scoresNear1() {
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of("ELEC_LV"), Set.of("ELEC_LV"), 10,
                TravelTimeEstimate.DEGRADED, 0, 0, PartsAvailabilityStatus.FULLY_STOCKED);
        CompetencyFitFactor factor = new CompetencyFitFactor();
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        // 0.7 * 1.0 (100% cert) + 0.3 * 1.0 (10/10 exp) = 1.0
        assertThat(bd.normalisedValue()).isCloseTo(1.0, within(0.001));
        assertThat(bd.degraded()).isFalse();
    }

    @Test
    void competency_noCerts_noExperience_scoresLow() {
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of(), Set.of("ELEC_LV"), 0,
                TravelTimeEstimate.DEGRADED, 0, 0, PartsAvailabilityStatus.FULLY_STOCKED);
        CompetencyFitFactor factor = new CompetencyFitFactor();
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        // 0.7 * 0.0 + 0.3 * 0.0 = 0.0
        assertThat(bd.normalisedValue()).isCloseTo(0.0, within(0.001));
    }

    @Test
    void competency_noRequiredCerts_scores1ForCoverage() {
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of(), Set.of(), 0,
                TravelTimeEstimate.DEGRADED, 0, 0, PartsAvailabilityStatus.FULLY_STOCKED);
        CompetencyFitFactor factor = new CompetencyFitFactor();
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        // coverage = 1.0 when no certs required, exp = 0
        assertThat(bd.normalisedValue()).isCloseTo(0.7, within(0.001));
    }

    // ── TravelEfficiencyFactor boundary tests ───────────────────────────────

    @Test
    void travel_zeroMinutes_scoresOne() {
        TravelEfficiencyFactor factor = new TravelEfficiencyFactor();
        ScoringContext ctx = travelCtx(UUID.randomUUID(), 0, false);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(1.0, within(0.001));
        assertThat(bd.degraded()).isFalse();
    }

    @Test
    void travel_horizonMinutes_scoresZero() {
        TravelEfficiencyFactor factor = new TravelEfficiencyFactor();
        ScoringContext ctx = travelCtx(UUID.randomUUID(), 120, false);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(0.0, within(0.001));
    }

    @Test
    void travel_beyondHorizon_clampedToZero() {
        TravelEfficiencyFactor factor = new TravelEfficiencyFactor();
        ScoringContext ctx = travelCtx(UUID.randomUUID(), 200, false);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isEqualTo(0.0);
    }

    @Test
    void travel_degraded_returnsNeutralAndSetsDegradedFlag() {
        TravelEfficiencyFactor factor = new TravelEfficiencyFactor();
        ScoringContext ctx = travelCtx(UUID.randomUUID(), 0, true);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(TravelEfficiencyFactor.DEGRADED_NEUTRAL, within(0.001));
        assertThat(bd.degraded()).isTrue();
    }

    // ── WorkloadFairnessFactor boundary tests ────────────────────────────────

    @Test
    void workload_atMean_scoresOne() {
        WorkloadFairnessFactor factor = new WorkloadFairnessFactor();
        ScoringContext ctx = workloadCtx(UUID.randomUUID(), 7.0, 7.0);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        // relativeOverload = 0, penalty = 0, normalised = 1.0
        assertThat(bd.normalisedValue()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void workload_belowMean_scoresOne() {
        WorkloadFairnessFactor factor = new WorkloadFairnessFactor();
        ScoringContext ctx = workloadCtx(UUID.randomUUID(), 4.0, 7.0);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void workload_zeroMean_returnsNeutral() {
        WorkloadFairnessFactor factor = new WorkloadFairnessFactor();
        ScoringContext ctx = workloadCtx(UUID.randomUUID(), 5.0, 0.0);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void workload_9hoursBelow7Mean_ranksLowerThan6Hours_AC4() {
        // AC-4: a technician at 9 booked hours must rank BELOW one at 6 hours against mean=7
        WorkloadFairnessFactor factor = new WorkloadFairnessFactor();
        ScoringContext ctx6 = workloadCtx(UUID.randomUUID(), 6.0, 7.0);
        ScoringContext ctx9 = workloadCtx(UUID.randomUUID(), 9.0, 7.0);
        double score6 = factor.normalise(ctx6, DEFAULT_WEIGHTS).normalisedValue();
        double score9 = factor.normalise(ctx9, DEFAULT_WEIGHTS).normalisedValue();
        assertThat(score6).isGreaterThan(score9);
    }

    @Test
    void workload_superLinearPenalty_exponent2_at50pctOverload() {
        // At 50% over mean: relativeOverload=0.5, penalty=pow(0.5,2)=0.25, normalised=0.75
        WorkloadFairnessFactor factor = new WorkloadFairnessFactor();
        ScoringContext ctx = workloadCtx(UUID.randomUUID(), 10.5, 7.0); // 50% over mean
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(0.75, within(0.01));
    }

    @Test
    void workload_doubleOverMean_fullPenalty() {
        // At 100% over mean: relativeOverload=1.0, penalty=1.0, normalised=0.0
        WorkloadFairnessFactor factor = new WorkloadFairnessFactor();
        ScoringContext ctx = workloadCtx(UUID.randomUUID(), 14.0, 7.0); // exactly 100% over
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(0.0, within(0.01));
    }

    // ── PartsAvailabilityFactor tests ────────────────────────────────────────

    @Test
    void parts_fullyStocked_scoresOne() {
        PartsAvailabilityFactor factor = new PartsAvailabilityFactor();
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, 0, 0,
                PartsAvailabilityStatus.FULLY_STOCKED);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void parts_unavailable_scoresNeutralNotZero() {
        // Parts may only influence rank as a soft factor — must never score 0
        PartsAvailabilityFactor factor = new PartsAvailabilityFactor();
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, 0, 0,
                PartsAvailabilityStatus.UNAVAILABLE);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isGreaterThan(0.0);
        assertThat(bd.normalisedValue()).isEqualTo(PartsAvailabilityFactor.UNAVAILABLE_SCORE);
    }

    @Test
    void parts_partiallyStocked_scoresPointSeven() {
        PartsAvailabilityFactor factor = new PartsAvailabilityFactor();
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, 0, 0,
                PartsAvailabilityStatus.PARTIALLY_STOCKED);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(0.7, within(0.001));
    }

    @Test
    void parts_collectable_scoresPointSix() {
        PartsAvailabilityFactor factor = new PartsAvailabilityFactor();
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, 0, 0,
                PartsAvailabilityStatus.COLLECTABLE);
        FactorBreakdown bd = factor.normalise(ctx, DEFAULT_WEIGHTS);
        assertThat(bd.normalisedValue()).isCloseTo(0.6, within(0.001));
    }

    // ── Engine composite + composite always in [0,1] ─────────────────────────

    @Test
    void composite_scoresAlwaysInZeroOne() {
        List<ScoringContext> candidates = buildCandidates(20, 7.0);
        List<ScoredCandidate> results = engine.rank(candidates, DEFAULT_WEIGHTS);
        for (ScoredCandidate sc : results) {
            assertThat(sc.compositeScore())
                    .isGreaterThanOrEqualTo(0.0)
                    .isLessThanOrEqualTo(1.0);
        }
    }

    // ── Determinism (AC-7) ───────────────────────────────────────────────────

    @Test
    void engine_deterministic_repeatedCallsProduceIdenticalOrder() {
        List<ScoringContext> candidates = buildCandidates(10, 7.0);
        List<ScoredCandidate> first  = engine.rank(candidates, DEFAULT_WEIGHTS);
        List<ScoredCandidate> second = engine.rank(candidates, DEFAULT_WEIGHTS);
        assertThat(first.stream().map(ScoredCandidate::technicianId).toList())
                .isEqualTo(second.stream().map(ScoredCandidate::technicianId).toList());
        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).compositeScore())
                    .isCloseTo(second.get(i).compositeScore(), within(0.0));
        }
    }

    // ── Tie-break ordering (AC-1) ─────────────────────────────────────────────

    @Test
    void engine_identicalScores_tieBreakByTechnicianId() {
        // All identical contexts → same composite, ordered ascending by id
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
        ScoringContext same = ctx(null, List.of("ELEC_LV"), Set.of("ELEC_LV"), 5,
                new TravelTimeEstimate(30, false), 7.0, 7.0, PartsAvailabilityStatus.FULLY_STOCKED);

        ScoringContext c1 = withId(id1, same);
        ScoringContext c2 = withId(id2, same);
        ScoringContext c3 = withId(id3, same);

        List<ScoredCandidate> ranked = engine.rank(List.of(c3, c1, c2), DEFAULT_WEIGHTS);
        assertThat(ranked.get(0).technicianId()).isEqualTo(id1);
        assertThat(ranked.get(1).technicianId()).isEqualTo(id2);
        assertThat(ranked.get(2).technicianId()).isEqualTo(id3);
    }

    // ── Zero-weight factor (AC-6) ─────────────────────────────────────────────

    @Test
    void engine_inactiveFactorExcludedFromComposite() {
        ScoringWeights zeroPartsWeight = new ScoringWeights(List.of(
                new ScoringWeights.WeightEntry(CompetencyFitFactor.CODE,    1.0, true),
                new ScoringWeights.WeightEntry(TravelEfficiencyFactor.CODE, 1.0, true),
                new ScoringWeights.WeightEntry(WorkloadFairnessFactor.CODE, 1.0, true),
                new ScoringWeights.WeightEntry(PartsAvailabilityFactor.CODE, 1.0, false) // inactive
        ), 2.0);

        UUID id = UUID.randomUUID();
        ScoringContext ctxPartsAvail   = ctx(id, List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, 7, 7,
                PartsAvailabilityStatus.FULLY_STOCKED);
        ScoringContext ctxPartsUnavail = ctx(id, List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, 7, 7,
                PartsAvailabilityStatus.UNAVAILABLE);

        List<ScoredCandidate> r1 = engine.rank(List.of(ctxPartsAvail), zeroPartsWeight);
        List<ScoredCandidate> r2 = engine.rank(List.of(ctxPartsUnavail), zeroPartsWeight);

        // With PARTS inactive, changing parts availability must not change score
        assertThat(r1.get(0).compositeScore()).isCloseTo(r2.get(0).compositeScore(), within(0.001));
    }

    @Test
    void engine_allFactorsZeroWeight_returnsZeroScoreInIdOrder() {
        ScoringWeights allInactive = new ScoringWeights(List.of(
                new ScoringWeights.WeightEntry(CompetencyFitFactor.CODE,     1.0, false),
                new ScoringWeights.WeightEntry(TravelEfficiencyFactor.CODE,  1.0, false),
                new ScoringWeights.WeightEntry(WorkloadFairnessFactor.CODE,  1.0, false),
                new ScoringWeights.WeightEntry(PartsAvailabilityFactor.CODE, 1.0, false)
        ), 2.0);

        UUID id1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("10000000-0000-0000-0000-000000000002");
        ScoringContext c1 = ctx(id1, List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, 0, 0,
                PartsAvailabilityStatus.FULLY_STOCKED);
        ScoringContext c2 = ctx(id2, List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, 0, 0,
                PartsAvailabilityStatus.FULLY_STOCKED);

        List<ScoredCandidate> ranked = engine.rank(List.of(c2, c1), allInactive);
        assertThat(ranked.get(0).compositeScore()).isEqualTo(0.0);
        assertThat(ranked.get(0).technicianId()).isEqualTo(id1);
    }

    // ── ScoringWeights exponent validation ───────────────────────────────────

    @Test
    void scoringWeights_exponentNotGreaterThan1_throws() {
        assertThatThrownBy(() -> new ScoringWeights(List.of(), 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workloadPenaltyExponent must be > 1");
    }

    // ── 200-candidate timing budget (AC-8) ───────────────────────────────────

    @Test
    void engine_200candidates_completesWithin150ms() {
        List<ScoringContext> candidates = buildCandidates(200, 7.0);
        long start = System.nanoTime();
        engine.rank(candidates, DEFAULT_WEIGHTS);
        long elapsed = System.nanoTime() - start;
        long elapsedMs = elapsed / 1_000_000;
        assertThat(elapsedMs)
                .as("200-candidate scoring must complete within 150ms in-JVM (got %d ms)", elapsedMs)
                .isLessThan(150L);
    }

    // ── Degraded travel sets candidate-level flag ─────────────────────────────

    @Test
    void engine_degradedTravel_setsPerCandidateDegradedFlag() {
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of("ELEC_LV"), Set.of("ELEC_LV"), 5,
                TravelTimeEstimate.DEGRADED, 7.0, 7.0, PartsAvailabilityStatus.FULLY_STOCKED);
        List<ScoredCandidate> ranked = engine.rank(List.of(ctx), DEFAULT_WEIGHTS);
        assertThat(ranked.get(0).degraded()).isTrue();
    }

    @Test
    void engine_singleCandidate_returnsValidBreakdown() {
        ScoringContext ctx = ctx(UUID.randomUUID(),
                List.of("ELEC_LV"), Set.of("ELEC_LV"), 5,
                new TravelTimeEstimate(30, false), 6.0, 7.0, PartsAvailabilityStatus.FULLY_STOCKED);
        List<ScoredCandidate> ranked = engine.rank(List.of(ctx), DEFAULT_WEIGHTS);
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).breakdown()).hasSize(4);
        assertThat(ranked.get(0).compositeScore()).isBetween(0.0, 1.0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ScoringContext ctx(UUID id, List<String> held, Set<String> required,
            int experience, TravelTimeEstimate travel, double booked, double mean,
            PartsAvailabilityStatus partsStatus) {
        return new ScoringContext(id == null ? UUID.randomUUID() : id,
                held, required, experience, travel, booked, mean, partsStatus);
    }

    private static ScoringContext travelCtx(UUID id, double minutes, boolean degraded) {
        return ctx(id, List.of(), Set.of(), 0,
                new TravelTimeEstimate(minutes, degraded), 7.0, 7.0,
                PartsAvailabilityStatus.FULLY_STOCKED);
    }

    private static ScoringContext workloadCtx(UUID id, double booked, double mean) {
        return ctx(id, List.of(), Set.of(), 0, TravelTimeEstimate.DEGRADED, booked, mean,
                PartsAvailabilityStatus.FULLY_STOCKED);
    }

    private static ScoringContext withId(UUID id, ScoringContext template) {
        return new ScoringContext(id,
                template.heldCertificationCodes(),
                template.requiredCertificationCodes(),
                template.priorJobTypeExperienceCount(),
                template.travelTime(),
                template.bookedHours(),
                template.teamMeanBookedHours(),
                template.partsAvailabilityStatus());
    }

    private static List<ScoringContext> buildCandidates(int count, double teamMean) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> ctx(UUID.randomUUID(),
                        List.of("CERT_" + (i % 3)),
                        Set.of("CERT_" + (i % 3)),
                        i % 10,
                        new TravelTimeEstimate((i % 120), false),
                        teamMean + (i % 5) - 2.0,
                        teamMean,
                        i % 2 == 0
                                ? PartsAvailabilityStatus.FULLY_STOCKED
                                : PartsAvailabilityStatus.UNAVAILABLE))
                .toList();
    }
}
