package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.RetentionPeriodUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RetentionCutoffCalculator — verifies calendar arithmetic
 * for DAYS, MONTHS, and YEARS period units.
 */
class RetentionCutoffCalculatorTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private RetentionCutoffCalculator calculatorAt(Instant fixed) {
        Clock clock = Clock.fixed(fixed, UTC);
        return new RetentionCutoffCalculator(clock, "UTC");
    }

    @Test
    @DisplayName("DAYS: cutoff is exactly N days before now (DST-neutral subtraction)")
    void days_subtractsExactDays() {
        Instant now = Instant.parse("2026-03-15T12:00:00Z");
        RetentionCutoffCalculator calc = calculatorAt(now);

        Instant cutoff = calc.computeCutoff(90, RetentionPeriodUnit.DAYS);

        Instant expected = now.minus(90, ChronoUnit.DAYS);
        assertThat(cutoff).isEqualTo(expected);
    }

    @Test
    @DisplayName("MONTHS: cutoff preserves calendar month boundary semantics")
    void months_usesCalendarArithmetic() {
        // 2026-01-31 minus 1 month → 2025-12-31 (calendar month, not 31 fixed days)
        Instant now = Instant.parse("2026-01-31T00:00:00Z");
        RetentionCutoffCalculator calc = calculatorAt(now);

        Instant cutoff = calc.computeCutoff(1, RetentionPeriodUnit.MONTHS);

        assertThat(cutoff).isEqualTo(Instant.parse("2025-12-31T00:00:00Z"));
    }

    @Test
    @DisplayName("MONTHS: 12 months preserves year boundary")
    void months_12_equalsOneYear() {
        Instant now = Instant.parse("2026-06-15T09:30:00Z");
        RetentionCutoffCalculator calc = calculatorAt(now);

        Instant cutoff = calc.computeCutoff(12, RetentionPeriodUnit.MONTHS);

        assertThat(cutoff).isEqualTo(Instant.parse("2025-06-15T09:30:00Z"));
    }

    @Test
    @DisplayName("YEARS: cutoff subtracts calendar years")
    void years_subtractsCalendarYears() {
        Instant now = Instant.parse("2026-02-28T10:00:00Z");
        RetentionCutoffCalculator calc = calculatorAt(now);

        Instant cutoff = calc.computeCutoff(1, RetentionPeriodUnit.YEARS);

        assertThat(cutoff).isEqualTo(Instant.parse("2025-02-28T10:00:00Z"));
    }

    @Test
    @DisplayName("YEARS: leap-year edge — 2024-02-29 minus 1 year → 2023-02-28")
    void years_leapYear_clampsToEndOfFebruary() {
        Instant now = Instant.parse("2024-02-29T00:00:00Z");
        RetentionCutoffCalculator calc = calculatorAt(now);

        Instant cutoff = calc.computeCutoff(1, RetentionPeriodUnit.YEARS);

        assertThat(cutoff).isEqualTo(Instant.parse("2023-02-28T00:00:00Z"));
    }

    @Test
    @DisplayName("DAYS: 365-day floor matches one-year audit minimum")
    void days_365_satisfiesAuditFloor() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        RetentionCutoffCalculator calc = calculatorAt(now);

        Instant cutoff = calc.computeCutoff(365, RetentionPeriodUnit.DAYS);

        assertThat(cutoff).isEqualTo(now.minus(365, ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("MONTHS: 24-month retention period produces cutoff 2 years ago")
    void months_24_produces2YearsAgo() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        RetentionCutoffCalculator calc = calculatorAt(now);

        Instant cutoff = calc.computeCutoff(24, RetentionPeriodUnit.MONTHS);

        assertThat(cutoff).isEqualTo(Instant.parse("2024-07-01T00:00:00Z"));
    }
}
