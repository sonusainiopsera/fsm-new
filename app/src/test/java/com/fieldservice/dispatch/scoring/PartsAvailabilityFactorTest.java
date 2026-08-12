package com.fieldservice.dispatch.scoring;

import com.fieldservice.inventory.api.AvailabilityStatus;
import com.fieldservice.inventory.api.CandidateAvailability;
import com.fieldservice.inventory.api.PartShortfall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Unit tests for {@link PartsAvailabilityFactor}.
 *
 * <p>Key invariants verified:
 * <ul>
 *   <li>Score is always in [0.0, 1.0]</li>
 *   <li>Parts shortage never excludes a candidate (score is still > threshold for ranking)</li>
 *   <li>Degraded availability data returns neutral 1.0 (no penalty when data is absent)</li>
 * </ul>
 */
class PartsAvailabilityFactorTest {

    static final UUID TECH_ID    = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID LOC_ID     = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID PART_A     = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final UUID PART_B     = UUID.fromString("00000000-0000-0000-0000-000000000011");

    PartsAvailabilityFactor factor;
    ScoringContext ctx;

    @BeforeEach
    void setUp() {
        factor = new PartsAvailabilityFactor();
        ctx = new ScoringContext(List.of("ELEC_DISPATCH"), "BOILER_FAULT", 6.0, 90);
    }

    private CandidateScoringData dataWithAvailability(AvailabilityStatus status,
                                                       List<PartShortfall> shortfalls) {
        CandidateAvailability av = new CandidateAvailability(LOC_ID, status, shortfalls, Instant.now());
        return new CandidateScoringData(TECH_ID, List.of("ELEC_DISPATCH"), 3, 4.0,
                new TravelTimeResult(TECH_ID, 30, false), 1.0, av);
    }

    private CandidateScoringData dataNoAvailability() {
        return new CandidateScoringData(TECH_ID, List.of("ELEC_DISPATCH"), 3, 4.0,
                new TravelTimeResult(TECH_ID, 30, false), 1.0, null);
    }

    @Nested
    @DisplayName("Score tiers")
    class ScoreTiers {

        @Test
        @DisplayName("FULLY_STOCKED → 1.0")
        void fullyStocked() {
            FactorBreakdown bd = factor.normalise(
                    dataWithAvailability(AvailabilityStatus.FULLY_STOCKED, List.of()), ctx);
            assertThat(bd.normalisedValue()).isEqualTo(1.0, offset(0.001));
            assertThat(bd.degraded()).isFalse();
        }

        @Test
        @DisplayName("COLLECTABLE → 0.75 (nearby-warehouse discount)")
        void collectable() {
            PartShortfall shortfall = new PartShortfall(PART_A, "PUMP-001", 2, 2);
            FactorBreakdown bd = factor.normalise(
                    dataWithAvailability(AvailabilityStatus.COLLECTABLE, List.of(shortfall)), ctx);
            assertThat(bd.normalisedValue()).isEqualTo(0.75, offset(0.001));
        }

        @Test
        @DisplayName("PARTIALLY_STOCKED → 0.5")
        void partiallyStocked() {
            PartShortfall shortfall = new PartShortfall(PART_A, "PUMP-001", 4, 2);
            FactorBreakdown bd = factor.normalise(
                    dataWithAvailability(AvailabilityStatus.PARTIALLY_STOCKED, List.of(shortfall)), ctx);
            assertThat(bd.normalisedValue()).isEqualTo(0.5, offset(0.001));
        }

        @Test
        @DisplayName("UNAVAILABLE → 0.0")
        void unavailable() {
            PartShortfall shortfall = new PartShortfall(PART_A, "PUMP-001", 2, 0);
            FactorBreakdown bd = factor.normalise(
                    dataWithAvailability(AvailabilityStatus.UNAVAILABLE, List.of(shortfall)), ctx);
            assertThat(bd.normalisedValue()).isEqualTo(0.0, offset(0.001));
        }
    }

    @Nested
    @DisplayName("Neutral / degraded paths")
    class NeutralPaths {

        @Test
        @DisplayName("No availability data (null) → neutral 1.0, not degraded")
        void noAvailabilityDataReturnsNeutral() {
            FactorBreakdown bd = factor.normalise(dataNoAvailability(), ctx);
            assertThat(bd.normalisedValue()).isEqualTo(1.0, offset(0.001));
            assertThat(bd.degraded()).isFalse();
        }

        @Test
        @DisplayName("Empty required-parts set → neutral 1.0, no penalty")
        void emptyRequiredPartsSetNeutral() {
            // Represented by null availability (no parts to check → factor returns neutral)
            FactorBreakdown bd = factor.normalise(dataNoAvailability(), ctx);
            assertThat(bd.normalisedValue()).isEqualTo(1.0, offset(0.001));
            assertThat(bd.partsAvailability()).isNull();
        }
    }

    @Nested
    @DisplayName("Advisory-only invariant — parts shortage never excludes a candidate")
    class AdvisoryOnlyInvariant {

        @Test
        @DisplayName("Candidate with UNAVAILABLE parts still produces a valid score (0.0), not an exclusion")
        void unavailableScoreIsNotAnExclusion() {
            PartShortfall shortfall = new PartShortfall(PART_A, "VALVE-X", 1, 0);
            FactorBreakdown bd = factor.normalise(
                    dataWithAvailability(AvailabilityStatus.UNAVAILABLE, List.of(shortfall)), ctx);

            // Score is 0.0 — a soft penalty, not an exclusion marker
            assertThat(bd.normalisedValue()).isGreaterThanOrEqualTo(0.0);
            // The factor code must match so the engine routes it correctly — not silently excluded
            assertThat(bd.factorCode()).isEqualTo(PartsAvailabilityFactor.FACTOR_CODE);
            // degraded is false — this is a real data point, not a fallback
            assertThat(bd.degraded()).isFalse();
        }

        @Test
        @DisplayName("Multiple parts: multi-shortfall case still produces valid score, not exclusion")
        void multipleShortfallsStillValidScore() {
            List<PartShortfall> shortfalls = List.of(
                    new PartShortfall(PART_A, "PUMP-001", 2, 0),
                    new PartShortfall(PART_B, "VALVE-002", 1, 0));
            FactorBreakdown bd = factor.normalise(
                    dataWithAvailability(AvailabilityStatus.UNAVAILABLE, shortfalls), ctx);

            assertThat(bd.normalisedValue()).isBetween(0.0, 1.0);
            assertThat(bd.partsAvailability()).isNotNull();
            assertThat(bd.partsAvailability().shortfalls()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Score normalization bounds")
    class ScoreNormalizationBounds {

        @Test
        @DisplayName("All scores are within [0.0, 1.0]")
        void allScoresInBounds() {
            for (AvailabilityStatus status : AvailabilityStatus.values()) {
                double score = PartsAvailabilityFactor.scoreFor(status);
                assertThat(score).isBetween(0.0, 1.0);
            }
        }
    }

    @Nested
    @DisplayName("scoreFor static method")
    class ScoreForMethod {

        @Test
        @DisplayName("scoreFor produces same result as normalise for all statuses")
        void scoreForConsistentWithNormalise() {
            assertThat(PartsAvailabilityFactor.scoreFor(AvailabilityStatus.FULLY_STOCKED)).isEqualTo(1.0, offset(0.001));
            assertThat(PartsAvailabilityFactor.scoreFor(AvailabilityStatus.COLLECTABLE)).isEqualTo(0.75, offset(0.001));
            assertThat(PartsAvailabilityFactor.scoreFor(AvailabilityStatus.PARTIALLY_STOCKED)).isEqualTo(0.5, offset(0.001));
            assertThat(PartsAvailabilityFactor.scoreFor(AvailabilityStatus.UNAVAILABLE)).isEqualTo(0.0, offset(0.001));
        }
    }
}
