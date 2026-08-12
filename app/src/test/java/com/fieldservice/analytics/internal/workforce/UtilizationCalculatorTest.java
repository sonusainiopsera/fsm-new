package com.fieldservice.analytics.internal.workforce;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link UtilizationCalculator} (WO-163 AC-1, AC-8).
 *
 * <p>Includes the mandatory sum-of-numerators vs mean-of-ratios contrast test (AC-1).
 */
class UtilizationCalculatorTest {

    private static final UUID TECH_A = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID TECH_B = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final int ISO_YEAR = 2026;
    private static final int ISO_WEEK = 33;

    // ── Per-technician ratio ────────────────────────────────────────────────────

    @Test
    void perTech_fullUtilization_rateIsOne() {
        var rows = List.of(new WorkforceAggregationRepository.LabourWeekRow(
                TECH_A, ISO_YEAR, ISO_WEEK, 2400, 5)); // 2400 min field = 40h
        var shiftMap = Map.of(new UtilizationCalculator.TechWeekKey(TECH_A, ISO_YEAR, ISO_WEEK), 2400);

        var results = UtilizationCalculator.computePerTechnician(rows, shiftMap);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).value()).isEqualByComparingTo("1.000000");
        assertThat(results.get(0).incompleteData()).isFalse();
        assertThat(results.get(0).degraded()).isFalse();
    }

    @Test
    void perTech_partialUtilization_rateComputedCorrectly() {
        var rows = List.of(new WorkforceAggregationRepository.LabourWeekRow(
                TECH_A, ISO_YEAR, ISO_WEEK, 1200, 5)); // 1200 / 2400 = 0.5
        var shiftMap = Map.of(new UtilizationCalculator.TechWeekKey(TECH_A, ISO_YEAR, ISO_WEEK), 2400);

        var results = UtilizationCalculator.computePerTechnician(rows, shiftMap);

        assertThat(results.get(0).value()).isEqualByComparingTo("0.500000");
    }

    @Test
    void perTech_zeroFieldTime_rateIsZero_notExcluded() {
        // AC-2 note: 0% utilization is valid data and must NOT be excluded
        var rows = List.of(new WorkforceAggregationRepository.LabourWeekRow(
                TECH_A, ISO_YEAR, ISO_WEEK, 0, 0));
        var shiftMap = Map.of(new UtilizationCalculator.TechWeekKey(TECH_A, ISO_YEAR, ISO_WEEK), 2400);

        var results = UtilizationCalculator.computePerTechnician(rows, shiftMap);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).value()).isEqualByComparingTo("0");
        assertThat(results.get(0).degraded()).isFalse();
    }

    @Test
    void perTech_missingShiftMinutes_incompleteData_nullValue() {
        var rows = List.of(new WorkforceAggregationRepository.LabourWeekRow(
                TECH_A, ISO_YEAR, ISO_WEEK, 1800, 5));
        // No entry in shiftMap → incomplete
        var results = UtilizationCalculator.computePerTechnician(rows, Map.of());

        assertThat(results.get(0).value()).isNull();
        assertThat(results.get(0).incompleteData()).isTrue();
        assertThat(results.get(0).fieldMinutes()).isEqualTo(1800);
    }

    @Test
    void perTech_overOneHundredPercent_degradedFlag() {
        var rows = List.of(new WorkforceAggregationRepository.LabourWeekRow(
                TECH_A, ISO_YEAR, ISO_WEEK, 3000, 6)); // more than shift
        var shiftMap = Map.of(new UtilizationCalculator.TechWeekKey(TECH_A, ISO_YEAR, ISO_WEEK), 2400);

        var results = UtilizationCalculator.computePerTechnician(rows, shiftMap);

        assertThat(results.get(0).degraded()).isTrue();
        assertThat(results.get(0).degradedReason())
                .isEqualTo(UtilizationCalculator.DEGRADED_REASON_OVERLAP);
        // Value NOT clamped — transparency preferred
        assertThat(results.get(0).value()).isGreaterThan(BigDecimal.ONE);
    }

    // ── Team rollup: sum-of-numerators (AC-1 contrast test) ───────────────────

    /**
     * AC-1 contrast test: team utilization MUST be sum(numerators)/sum(denominators),
     * NOT mean(per-tech rates). This test asserts they differ and the correct one is used.
     *
     * <p>Scenario:
     * - Tech A: logged 2400 min, shift 2400 min → rate = 1.0 (100%)
     * - Tech B: logged    60 min, shift 2400 min → rate = 0.025 (2.5%)
     *
     * <p>Mean-of-ratios: (1.0 + 0.025) / 2 = 0.5125  ← WRONG
     * Sum-of-numerators: (2400 + 60) / (2400 + 2400) = 2460/4800 = 0.5125  ← coincidence here
     *
     * <p>Use asymmetric denominators to distinguish:
     * - Tech A: logged 2400 min, shift 2400 min → rate = 1.0
     * - Tech B: logged  120 min, shift  480 min → rate = 0.25
     *
     * <p>Mean-of-ratios: (1.0 + 0.25) / 2 = 0.625
     * Sum-of-numerators: 2520 / 2880 = 0.875  ← different, and correct
     */
    @Test
    void teamRollup_sumOfNumerators_differFromMeanOfRatios_correctOneUsed() {
        var rows = List.of(
                new WorkforceAggregationRepository.LabourWeekRow(TECH_A, ISO_YEAR, ISO_WEEK, 2400, 5),
                new WorkforceAggregationRepository.LabourWeekRow(TECH_B, ISO_YEAR, ISO_WEEK,  120, 1));
        var shiftMap = Map.of(
                new UtilizationCalculator.TechWeekKey(TECH_A, ISO_YEAR, ISO_WEEK), 2400,
                new UtilizationCalculator.TechWeekKey(TECH_B, ISO_YEAR, ISO_WEEK),  480);

        var perTech = UtilizationCalculator.computePerTechnician(rows, shiftMap);
        var team = UtilizationCalculator.computeTeamRollup(perTech);

        // Verify per-tech rates
        BigDecimal rateA = perTech.stream()
                .filter(r -> r.technicianId().equals(TECH_A)).findFirst().orElseThrow().value();
        BigDecimal rateB = perTech.stream()
                .filter(r -> r.technicianId().equals(TECH_B)).findFirst().orElseThrow().value();
        assertThat(rateA).isEqualByComparingTo("1.000000");
        assertThat(rateB).isEqualByComparingTo("0.250000");

        double meanOfRatios = (rateA.doubleValue() + rateB.doubleValue()) / 2.0; // 0.625

        // Sum-of-numerators: (2400+120)/(2400+480) = 2520/2880 ≈ 0.875
        double sumOfNumerators = team.value() != null ? team.value().doubleValue() : Double.NaN;

        // They MUST differ — the contract of AC-1
        assertThat(Math.abs(sumOfNumerators - meanOfRatios)).isGreaterThan(0.001);

        // And the correct one (sum-of-numerators) must equal 2520/2880
        assertThat(sumOfNumerators).isCloseTo(2520.0 / 2880.0, within(0.000001));
        assertThat(team.numerator()).isEqualByComparingTo("2520");
        assertThat(team.denominator()).isEqualByComparingTo("2880");
    }

    @Test
    void teamRollup_allIncomplete_nullValue_incompleteDataTrue() {
        var rows = List.of(
                new WorkforceAggregationRepository.LabourWeekRow(TECH_A, ISO_YEAR, ISO_WEEK, 1200, 5),
                new WorkforceAggregationRepository.LabourWeekRow(TECH_B, ISO_YEAR, ISO_WEEK,  600, 3));

        var perTech = UtilizationCalculator.computePerTechnician(rows, Map.of()); // empty shift map
        var team = UtilizationCalculator.computeTeamRollup(perTech);

        assertThat(team.value()).isNull();
        assertThat(team.incompleteData()).isTrue();
        assertThat(team.sampleCount()).isEqualTo(2);
    }

    @Test
    void teamRollup_mixedCompleteAndIncomplete_incompleteDataFlagged() {
        var rows = List.of(
                new WorkforceAggregationRepository.LabourWeekRow(TECH_A, ISO_YEAR, ISO_WEEK, 2400, 5),
                new WorkforceAggregationRepository.LabourWeekRow(TECH_B, ISO_YEAR, ISO_WEEK,  600, 3));
        var shiftMap = Map.of(
                new UtilizationCalculator.TechWeekKey(TECH_A, ISO_YEAR, ISO_WEEK), 2400);
        // TECH_B not in shift map → incomplete

        var perTech = UtilizationCalculator.computePerTechnician(rows, shiftMap);
        var team = UtilizationCalculator.computeTeamRollup(perTech);

        // Team still computes from the complete row, but flags incomplete
        assertThat(team.value()).isEqualByComparingTo("1.000000"); // only TECH_A contributes
        assertThat(team.incompleteData()).isTrue();
    }
}
