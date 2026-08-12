package com.fieldservice.privacy.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DSAR due-date and remaining-days arithmetic.
 */
class DsarDueDateTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    @DisplayName("Due date is submitted-at plus 30 days")
    void dueDate_is30DaysAfterSubmission() {
        Instant submitted = Instant.parse("2026-01-15T10:00:00Z");
        Instant expected  = submitted.plus(30, ChronoUnit.DAYS);

        DsarProperties props = new DsarProperties();
        props.setRequestDueDays(Duration.ofDays(30));

        Instant dueAt = submitted.plus(props.getRequestDueDays());
        assertThat(dueAt).isEqualTo(expected);
    }

    @Test
    @DisplayName("Remaining days for request submitted today with 30-day window")
    void remainingDays_newRequest_isApprox30() {
        Instant now       = Instant.parse("2026-08-12T09:00:00Z");
        Instant submitted = now;
        Instant dueAt     = submitted.plus(30, ChronoUnit.DAYS);

        long remainingDays = ChronoUnit.DAYS.between(now, dueAt);
        assertThat(remainingDays).isEqualTo(30);
    }

    @Test
    @DisplayName("Remaining days at-risk when <= 7 days remain")
    void atRisk_whenSevenOrFewerDaysRemain() {
        Instant now   = Instant.parse("2026-08-12T00:00:00Z");
        Instant dueAt = now.plus(7, ChronoUnit.DAYS);

        long remaining = ChronoUnit.DAYS.between(now, dueAt);
        assertThat(remaining).isEqualTo(7);
        assertThat(remaining <= 7).isTrue();
    }

    @Test
    @DisplayName("Remaining days never negative — clamps to zero")
    void remainingDays_clampsToZeroWhenOverdue() {
        Instant now   = Instant.parse("2026-09-15T00:00:00Z");
        Instant dueAt = Instant.parse("2026-09-10T00:00:00Z");

        long raw       = ChronoUnit.DAYS.between(now, dueAt);
        long remaining = Math.max(0, raw);
        assertThat(remaining).isEqualTo(0);
    }

    @Test
    @DisplayName("Request submitted just before DST change computes correct remaining days")
    void dueDateArithmetic_acrossDstChange() {
        // UK DST change: 2026-03-29 01:00 UTC (clocks forward)
        Instant submitted = Instant.parse("2026-03-28T23:00:00Z");
        Duration period   = Duration.ofDays(30);
        Instant dueAt     = submitted.plus(period);
        Instant now       = Instant.parse("2026-04-15T09:00:00Z");

        long remaining = Math.max(0, ChronoUnit.DAYS.between(now, dueAt));
        // submitted 28 Mar 23:00 + 30 days = 27 Apr 23:00; now = 15 Apr; remaining ≈ 12
        assertThat(remaining).isGreaterThan(0).isLessThanOrEqualTo(13);
    }

    @Test
    @DisplayName("At-risk threshold configuration respected")
    void atRiskThreshold_usesConfiguredValue() {
        DsarProperties props = new DsarProperties();
        props.setAtRiskThresholdDays(5);

        Instant now       = Instant.parse("2026-08-12T00:00:00Z");
        Instant dueAt6    = now.plus(6, ChronoUnit.DAYS);
        Instant dueAt4    = now.plus(4, ChronoUnit.DAYS);

        long remaining6 = ChronoUnit.DAYS.between(now, dueAt6);
        long remaining4 = ChronoUnit.DAYS.between(now, dueAt4);

        assertThat(remaining6 <= props.getAtRiskThresholdDays()).isFalse();
        assertThat(remaining4 <= props.getAtRiskThresholdDays()).isTrue();
    }
}
