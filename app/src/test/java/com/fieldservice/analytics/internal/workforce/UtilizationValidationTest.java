package com.fieldservice.analytics.internal.workforce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KPI Baseline Instrumentation Validation — Technician Utilization (WO-207).
 *
 * <h3>Metric formula</h3>
 * <pre>
 *   per_tech_rate  = field_minutes / shift_minutes
 *   team_rollup    = SUM(all field_minutes) / SUM(all shift_minutes)   [NOT mean of per-tech rates]
 * </pre>
 *
 * <h3>Special cases</h3>
 * <ul>
 *   <li>Zero shift_minutes → {@code incompleteData=true}, {@code value=null}</li>
 *   <li>field_minutes &gt; shift_minutes → {@code degraded=true}, reason={@code DATA_QUALITY_OVERLAP}</li>
 * </ul>
 *
 * <h3>Golden dataset provenance</h3>
 * See {@code src/test/resources/golden/kpi-expected-values.json} section
 * {@code technician_utilization}.
 */
@DisplayName("Technician Utilization — KPI Instrumentation Validation (WO-207)")
class UtilizationValidationTest {

    private static final int ISO_YEAR = 2026;
    private static final int ISO_WEEK = 15;

    private static final UUID TECH_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TECH_B = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID TECH_C = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");

    // ─── AC-4: per-tech rates match golden dataset ───────────────────────────────

    @Test
    @DisplayName("AC-4: per-technician rates match hand-derived expected values (dataset A)")
    void perTechRates_matchGoldenValues_datasetA() {
        List<WorkforceAggregationRepository.LabourWeekRow> labour = List.of(
                labourRow(TECH_A, 1800),
                labourRow(TECH_B, 480),
                labourRow(TECH_C, 2400));

        Map<UtilizationCalculator.TechWeekKey, Integer> shifts = Map.of(
                key(TECH_A), 2400,
                key(TECH_B), 2400,
                key(TECH_C), 2400);

        List<UtilizationCalculator.TechWeekResult> results =
                UtilizationCalculator.computePerTechnician(labour, shifts);

        // TECH_A: 1800/2400 = 0.75
        UtilizationCalculator.TechWeekResult a = techFor(results, TECH_A);
        assertThat(a.value()).isEqualByComparingTo("0.750000");
        assertThat(a.incompleteData()).isFalse();
        assertThat(a.degraded()).isFalse();

        // TECH_B: 480/2400 = 0.20
        UtilizationCalculator.TechWeekResult b = techFor(results, TECH_B);
        assertThat(b.value()).isEqualByComparingTo("0.200000");

        // TECH_C: 2400/2400 = 1.00
        UtilizationCalculator.TechWeekResult c = techFor(results, TECH_C);
        assertThat(c.value()).isEqualByComparingTo("1.000000");
    }

    @Test
    @DisplayName("AC-4: team rollup uses sum-of-numerators NOT mean of per-tech rates")
    void teamRollup_sumOfNumerators_notMeanOfRates() {
        List<WorkforceAggregationRepository.LabourWeekRow> labour = List.of(
                labourRow(TECH_A, 1800),
                labourRow(TECH_B, 480),
                labourRow(TECH_C, 2400));

        Map<UtilizationCalculator.TechWeekKey, Integer> shifts = Map.of(
                key(TECH_A), 2400,
                key(TECH_B), 2400,
                key(TECH_C), 2400);

        List<UtilizationCalculator.TechWeekResult> perTech =
                UtilizationCalculator.computePerTechnician(labour, shifts);
        UtilizationCalculator.TeamRollupResult rollup =
                UtilizationCalculator.computeTeamRollup(perTech);

        // SUM: (1800+480+2400) / (2400+2400+2400) = 4680/7200 = 0.65
        assertThat(rollup.value()).isEqualByComparingTo("0.650000");
        assertThat(rollup.numerator()).isEqualByComparingTo("4680");
        assertThat(rollup.denominator()).isEqualByComparingTo("7200");
        assertThat(rollup.incompleteData()).isFalse();
        assertThat(rollup.degraded()).isFalse();
    }

    // ─── AC-4: zero logged time → incompleteData ─────────────────────────────────

    @Test
    @DisplayName("AC-4: technician with no shift minutes has incompleteData=true and null rate")
    void zeroShiftMinutes_incompleteDataTrue_nullRate() {
        List<WorkforceAggregationRepository.LabourWeekRow> labour = List.of(
                labourRow(TECH_A, 0));

        // No shift entry for TECH_A → no shift minutes
        Map<UtilizationCalculator.TechWeekKey, Integer> shifts = Map.of();

        List<UtilizationCalculator.TechWeekResult> results =
                UtilizationCalculator.computePerTechnician(labour, shifts);

        UtilizationCalculator.TechWeekResult a = techFor(results, TECH_A);
        assertThat(a.value()).isNull();
        assertThat(a.incompleteData()).isTrue();
    }

    // ─── AC-4: field minutes > shift minutes → degraded ──────────────────────────

    @Test
    @DisplayName("AC-4: field_minutes exceeding shift_minutes marks degraded=DATA_QUALITY_OVERLAP")
    void fieldMinutesExceedShift_degradedWithOverlapReason() {
        List<WorkforceAggregationRepository.LabourWeekRow> labour = List.of(
                labourRow(TECH_A, 2640)); // 2640 > 2400 shift

        Map<UtilizationCalculator.TechWeekKey, Integer> shifts = Map.of(
                key(TECH_A), 2400);

        List<UtilizationCalculator.TechWeekResult> results =
                UtilizationCalculator.computePerTechnician(labour, shifts);

        UtilizationCalculator.TechWeekResult a = techFor(results, TECH_A);
        // Rate is stored (not null) — 2640/2400 = 1.10
        assertThat(a.value()).isEqualByComparingTo("1.100000");
        assertThat(a.degraded()).isTrue();
        assertThat(a.degradedReason()).isEqualTo(UtilizationCalculator.DEGRADED_REASON_OVERLAP);
    }

    // ─── AC-9: zero-denominator team rollup returns null ─────────────────────────

    @Test
    @DisplayName("AC-9: team rollup with no shift data returns null value (not-available)")
    void teamRollup_noShiftData_returnsNullValue() {
        List<WorkforceAggregationRepository.LabourWeekRow> labour = List.of(
                labourRow(TECH_A, 0));

        List<UtilizationCalculator.TechWeekResult> perTech =
                UtilizationCalculator.computePerTechnician(labour, Map.of());
        UtilizationCalculator.TeamRollupResult rollup =
                UtilizationCalculator.computeTeamRollup(perTech);

        assertThat(rollup.value()).isNull();
        assertThat(rollup.incompleteData()).isTrue();
    }

    // ─── Partial week (fewer active days) ────────────────────────────────────────

    @Test
    @DisplayName("AC-4: partial week with 2 active days computed the same as a full week")
    void partialWeek_computedNormally_notExtrapolated() {
        // Partial week: distinctDays=2, but field/shift minutes are actual — no extrapolation
        WorkforceAggregationRepository.LabourWeekRow row =
                new WorkforceAggregationRepository.LabourWeekRow(TECH_A, ISO_YEAR, ISO_WEEK, 960, 2);

        List<UtilizationCalculator.TechWeekResult> results =
                UtilizationCalculator.computePerTechnician(List.of(row), Map.of(key(TECH_A), 1920));

        UtilizationCalculator.TechWeekResult a = techFor(results, TECH_A);
        assertThat(a.value()).isEqualByComparingTo("0.500000"); // 960/1920
        assertThat(a.incompleteData()).isFalse();
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static WorkforceAggregationRepository.LabourWeekRow labourRow(UUID techId, int fieldMinutes) {
        return new WorkforceAggregationRepository.LabourWeekRow(techId, ISO_YEAR, ISO_WEEK, fieldMinutes, 5);
    }

    private static UtilizationCalculator.TechWeekKey key(UUID techId) {
        return new UtilizationCalculator.TechWeekKey(techId, ISO_YEAR, ISO_WEEK);
    }

    private static UtilizationCalculator.TechWeekResult techFor(
            List<UtilizationCalculator.TechWeekResult> results, UUID techId) {
        return results.stream()
                .filter(r -> techId.equals(r.technicianId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result for technician: " + techId));
    }
}
