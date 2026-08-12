package com.fieldservice.dispatch.scoring;

import com.fieldservice.dispatch.scoring.factors.PartsAvailabilityFactor;
import com.fieldservice.inventory.api.PartsAvailabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.fieldservice.dispatch.scoring.factors.PartsAvailabilityFactor.NEUTRAL_FLOOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link PartsAvailabilityFactor}.
 *
 * <p>Covers AC-1 (ratio-based normalisation), AC-2 (advisory-only guarantee),
 * AC-8 (all tiers + empty requirements + degraded path).
 */
class PartsAvailabilityFactorTest {

    private static final double WEIGHT = 0.25;
    private static final double DISCOUNT = 0.6;
    private static final ScoringWeights WEIGHTS = new ScoringWeights(
            List.of(new ScoringWeights.WeightEntry(PartsAvailabilityFactor.CODE, WEIGHT, true)),
            2.0);

    private final PartsAvailabilityFactor factor = new PartsAvailabilityFactor(DISCOUNT);

    // ── Fully stocked ──────────────────────────────────────────────────────────

    @Test
    void fullyStocked_ratio1_scoresOne() {
        ScoringContext ctx = ctx(PartsAvailabilityStatus.FULLY_STOCKED, 1.0);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue()).isCloseTo(1.0, within(0.001));
        assertThat(bd.weightedContribution()).isCloseTo(WEIGHT * 1.0, within(0.001));
        assertThat(bd.degraded()).isFalse();
    }

    // ── Partially stocked ─────────────────────────────────────────────────────

    @Test
    void partiallyStocked_ratio08_scores08() {
        ScoringContext ctx = ctx(PartsAvailabilityStatus.PARTIALLY_STOCKED, 0.8);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue()).isCloseTo(0.8, within(0.001));
        assertThat(bd.rawValue()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void partiallyStocked_lowRatio_clampsToNeutralFloor() {
        ScoringContext ctx = ctx(PartsAvailabilityStatus.PARTIALLY_STOCKED, 0.2);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        // ratio 0.2 < NEUTRAL_FLOOR 0.5 → clamped to NEUTRAL_FLOOR
        assertThat(bd.normalisedValue()).isCloseTo(NEUTRAL_FLOOR, within(0.001));
    }

    @Test
    void partiallyStocked_ratio05_atFloor() {
        ScoringContext ctx = ctx(PartsAvailabilityStatus.PARTIALLY_STOCKED, 0.5);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue()).isCloseTo(NEUTRAL_FLOOR, within(0.001));
    }

    // ── Collectable ───────────────────────────────────────────────────────────

    @Test
    void collectable_zeroOnVan_appliesFullDiscount() {
        ScoringContext ctx = ctx(PartsAvailabilityStatus.COLLECTABLE, 0.0);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        // 0.0 + 0.6 * (1 - 0.0) = 0.6
        assertThat(bd.normalisedValue()).isCloseTo(DISCOUNT, within(0.001));
    }

    @Test
    void collectable_halfOnVan_blendedScore() {
        ScoringContext ctx = ctx(PartsAvailabilityStatus.COLLECTABLE, 0.5);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        // 0.5 + 0.6 * 0.5 = 0.8
        assertThat(bd.normalisedValue()).isCloseTo(0.8, within(0.001));
    }

    @Test
    void collectable_customDiscount_usedInScore() {
        PartsAvailabilityFactor customFactor = new PartsAvailabilityFactor(0.7);
        ScoringContext ctx = ctx(PartsAvailabilityStatus.COLLECTABLE, 0.0);
        FactorBreakdown bd = customFactor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue()).isCloseTo(0.7, within(0.001));
    }

    // ── Unavailable ───────────────────────────────────────────────────────────

    @Test
    void unavailable_zeroRatio_scoresNeutralFloor() {
        ScoringContext ctx = ctx(PartsAvailabilityStatus.UNAVAILABLE, 0.0);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue()).isCloseTo(NEUTRAL_FLOOR, within(0.001));
    }

    @Test
    void unavailable_neverScoresZero() {
        ScoringContext ctx = ctx(PartsAvailabilityStatus.UNAVAILABLE, 0.0);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue()).isGreaterThan(0.0);
        assertThat(bd.weightedContribution()).isGreaterThan(0.0);
    }

    // ── Empty requirements (AC-2 + AC-8 edge case) ────────────────────────────

    @Test
    void fullyStocked_withRatio1_noRequiredParts_isNeutral() {
        // When no parts required, orchestrator sets FULLY_STOCKED with ratio=1.0
        ScoringContext ctx = ctx(PartsAvailabilityStatus.FULLY_STOCKED, 1.0);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue()).isCloseTo(1.0, within(0.001));
    }

    // ── Advisory-only constraint (AC-2) ───────────────────────────────────────

    @ParameterizedTest
    @EnumSource(PartsAvailabilityStatus.class)
    void allStatuses_normalisedValueInClosedInterval(PartsAvailabilityStatus status) {
        double ratio = status == PartsAvailabilityStatus.FULLY_STOCKED ? 1.0 : 0.0;
        ScoringContext ctx = ctx(status, ratio);
        FactorBreakdown bd = factor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue())
                .as("normalisedValue must be in [0,1] for status %s", status)
                .isBetween(0.0, 1.0);
        assertThat(bd.normalisedValue())
                .as("normalisedValue must never be 0.0 (advisory-only, AC-2)")
                .isGreaterThan(0.0);
    }

    @Test
    void partsShortage_withWeightOne_doesNotExcludeCandidate() {
        // Even with full weight, a UNAVAILABLE candidate gets a non-zero score
        ScoringWeights fullWeight = new ScoringWeights(
                List.of(new ScoringWeights.WeightEntry(PartsAvailabilityFactor.CODE, 1.0, true)),
                2.0);

        ScoringContext ctx = ctx(PartsAvailabilityStatus.UNAVAILABLE, 0.0);
        FactorBreakdown bd = factor.normalise(ctx, fullWeight);

        assertThat(bd.normalisedValue()).isGreaterThan(0.0);
        assertThat(bd.weightedContribution()).isGreaterThan(0.0);
    }

    // ── Factor code ───────────────────────────────────────────────────────────

    @Test
    void factorCode_isPartsAvailability() {
        assertThat(factor.factorCode()).isEqualTo(PartsAvailabilityFactor.CODE);
    }

    // ── Constructor validation ────────────────────────────────────────────────

    @Test
    void constructor_discountOutOfRange_throws() {
        assertThatThrownBy(() -> new PartsAvailabilityFactor(1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartsAvailabilityFactor(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultConstructor_usesDefaultDiscount() {
        PartsAvailabilityFactor defaultFactor = new PartsAvailabilityFactor();
        ScoringContext ctx = ctx(PartsAvailabilityStatus.COLLECTABLE, 0.0);
        FactorBreakdown bd = defaultFactor.normalise(ctx, WEIGHTS);

        assertThat(bd.normalisedValue()).isCloseTo(
                PartsAvailabilityFactor.DEFAULT_NEARBY_COLLECTABLE_DISCOUNT, within(0.001));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ScoringContext ctx(PartsAvailabilityStatus status, double ratio) {
        return new ScoringContext(
                UUID.randomUUID(),
                List.of("ELEC_LV"),
                Set.of("ELEC_LV"),
                1,
                TravelTimeEstimate.DEGRADED,
                7.0,
                7.0,
                status,
                ratio);
    }
}
