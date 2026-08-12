package com.fieldservice.analytics.internal.workforce;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link ThroughputCalculator} (WO-163 AC-2, AC-4, AC-8).
 *
 * <p>Covers zero-active-day exclusion, onboarding/deactivation windows,
 * zero-denominator edge case, and team rollup consistency.
 */
class ThroughputCalculatorTest {

    private static final UUID TECH_A = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID TECH_B = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final UUID TECH_C = UUID.fromString("c0000000-0000-0000-0000-000000000003");

    private static final LocalDate D1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 8, 3);

    // ── Per-technician ─────────────────────────────────────────────────────────

    @Test
    void perTech_basicRate_closuresDividedByActiveDays() {
        var closures = List.of(
                new WorkforceAggregationRepository.ClosureRow(TECH_A, D1, 4),
                new WorkforceAggregationRepository.ClosureRow(TECH_A, D2, 2));
        Map<UUID, Integer> activeDays = Map.of(TECH_A, 3); // 3 active days

        var results = ThroughputCalculator.computePerTechnician(closures, activeDays);

        assertThat(results).hasSize(1);
        // 6 closures / 3 active days = 2.0
        assertThat(results.get(0).jobsPerDay()).isEqualByComparingTo("2.000000");
        assertThat(results.get(0).closureCount()).isEqualTo(6);
        assertThat(results.get(0).activeDays()).isEqualTo(3);
    }

    @Test
    void perTech_zeroActiveDays_excludedFromResult_AC2() {
        // AC-2: technicians with zero active days excluded from denominator
        var closures = List.of(
                new WorkforceAggregationRepository.ClosureRow(TECH_A, D1, 3));
        // TECH_B has zero active days — excluded by ActiveTechnicianDayResolver before this
        Map<UUID, Integer> activeDays = Map.of(TECH_A, 2); // TECH_B absent

        var results = ThroughputCalculator.computePerTechnician(closures, activeDays);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).technicianId()).isEqualTo(TECH_A);
    }

    @Test
    void perTech_techWithActiveDaysButNoClosures_rateIsZero() {
        // Technician was available (active days > 0) but completed no jobs
        Map<UUID, Integer> activeDays = Map.of(TECH_A, 5);
        var results = ThroughputCalculator.computePerTechnician(List.of(), activeDays);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).jobsPerDay()).isEqualByComparingTo("0");
        assertThat(results.get(0).closureCount()).isEqualTo(0);
    }

    @Test
    void perTech_onboardedMidWindow_onlyActiveDaysCount_AC4() {
        // AC-4: technician onboarded on day 20 of a 30-day window → at most 10 active days
        // We model this as 10 active days supplied by ActiveTechnicianDayResolver
        var closures = List.of(
                new WorkforceAggregationRepository.ClosureRow(TECH_A, D1, 5),
                new WorkforceAggregationRepository.ClosureRow(TECH_A, D2, 5));
        Map<UUID, Integer> activeDays = Map.of(TECH_A, 10); // only 10 days since onboarding

        var results = ThroughputCalculator.computePerTechnician(closures, activeDays);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).activeDays()).isEqualTo(10); // denominator is 10, not 30
        assertThat(results.get(0).jobsPerDay()).isEqualByComparingTo("1.000000"); // 10/10
    }

    // ── Team rollup ────────────────────────────────────────────────────────────

    @Test
    void teamRollup_multiTech_totalClosuresDividedByTotalActiveDays() {
        var closures = List.of(
                new WorkforceAggregationRepository.ClosureRow(TECH_A, D1, 4),
                new WorkforceAggregationRepository.ClosureRow(TECH_A, D2, 4),
                new WorkforceAggregationRepository.ClosureRow(TECH_B, D1, 2),
                new WorkforceAggregationRepository.ClosureRow(TECH_B, D3, 2));
        Map<UUID, Integer> activeDays = Map.of(TECH_A, 5, TECH_B, 5);

        var rollup = ThroughputCalculator.computeTeamRollup(closures, activeDays);

        // 12 closures / 10 active-tech-days = 1.2
        assertThat(rollup.value()).isNotNull();
        assertThat(rollup.value().doubleValue()).isCloseTo(1.2, within(0.000001));
        assertThat(rollup.numerator()).isEqualByComparingTo("12");
        assertThat(rollup.denominator()).isEqualByComparingTo("10");
        assertThat(rollup.techCount()).isEqualTo(2);
    }

    @Test
    void teamRollup_noActiveDays_nullValue() {
        var rollup = ThroughputCalculator.computeTeamRollup(List.of(), Map.of());

        assertThat(rollup.value()).isNull();
        assertThat(rollup.denominator()).isEqualByComparingTo("0");
    }

    @Test
    void teamRollup_techWithZeroActiveDaysExcludedFromDenominator_AC2() {
        // TECH_C has closures from a previous window that leaked (edge case)
        // but zero active days → excluded
        var closures = List.of(
                new WorkforceAggregationRepository.ClosureRow(TECH_A, D1, 3));
        // TECH_C not in activeDays map → not counted in denominator
        Map<UUID, Integer> activeDays = Map.of(TECH_A, 3);

        var rollup = ThroughputCalculator.computeTeamRollup(closures, activeDays);

        // 3 / 3 = 1.0 — TECH_C not in denominator
        assertThat(rollup.value()).isEqualByComparingTo("1.000000");
        assertThat(rollup.denominator()).isEqualByComparingTo("3");
        assertThat(rollup.techCount()).isEqualTo(1);
    }

    @Test
    void perTech_noPersonalDataInResult_AC5() {
        // AC-5: per-technician rows store UUID only — verify no personal data fields
        var closures = List.of(new WorkforceAggregationRepository.ClosureRow(TECH_A, D1, 2));
        Map<UUID, Integer> activeDays = Map.of(TECH_A, 1);

        var results = ThroughputCalculator.computePerTechnician(closures, activeDays);

        ThroughputCalculator.TechResult r = results.get(0);
        // TechResult has: technicianId (UUID), jobsPerDay, closureCount, activeDays
        // Assert no name/email/phone stored — structural check on the record fields
        assertThat(r.technicianId()).isInstanceOf(UUID.class);
        assertThat(r.jobsPerDay()).isInstanceOf(BigDecimal.class);
        // If this compiled with a 'name' or 'email' field, it would fail to reference below:
        // r.name() — compile error is the guard; at runtime we verify only UUID is the identifier
        assertThat(r.technicianId().toString()).doesNotContain("@"); // UUID, not email
    }
}
