package com.fieldservice.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BackoffCalculator unit tests (WO-005)")
class BackoffCalculatorTest {

    private static final Instant NOW = Instant.parse("2025-01-15T10:00:00Z");
    private static final long BASE_MS = 1_000;
    private static final long CAP_MS = 300_000;
    private static final double JITTER = 0.2;

    @Test
    @DisplayName("Attempt 0: delay is base with up to jitter")
    void attempt0_delayIsBase() {
        Instant next = BackoffCalculator.nextAttemptAt(BASE_MS, CAP_MS, JITTER, 0, NOW);
        long delayMs = next.toEpochMilli() - NOW.toEpochMilli();
        // base * 2^0 = 1000, jitter up to 20% → [1000, 1200]
        assertThat(delayMs).isBetween(1_000L, 1_200L);
    }

    @Test
    @DisplayName("Attempt 1: delay is 2 * base (exponential)")
    void attempt1_doublesDelay() {
        Instant next = BackoffCalculator.nextAttemptAt(BASE_MS, CAP_MS, JITTER, 1, NOW);
        long delayMs = next.toEpochMilli() - NOW.toEpochMilli();
        // base * 2^1 = 2000, jitter up to 20% → [2000, 2400]
        assertThat(delayMs).isBetween(2_000L, 2_400L);
    }

    @Test
    @DisplayName("Attempt 2: delay is 4 * base (exponential)")
    void attempt2_quadruplesBase() {
        Instant next = BackoffCalculator.nextAttemptAt(BASE_MS, CAP_MS, JITTER, 2, NOW);
        long delayMs = next.toEpochMilli() - NOW.toEpochMilli();
        // base * 2^2 = 4000, jitter up to 20% → [4000, 4800]
        assertThat(delayMs).isBetween(4_000L, 4_800L);
    }

    @Test
    @DisplayName("High attempt count is capped at cap")
    void highAttemptCount_cappedAtMax() {
        Instant next = BackoffCalculator.nextAttemptAt(BASE_MS, CAP_MS, JITTER, 30, NOW);
        long delayMs = next.toEpochMilli() - NOW.toEpochMilli();
        // cap = 300_000, jitter up to 20% → [300_000, 360_000]
        assertThat(delayMs).isBetween(300_000L, 360_000L);
    }

    @Test
    @DisplayName("Zero jitter fraction → delay equals exactly the exponential value")
    void zeroJitter_noRandomness() {
        Instant next = BackoffCalculator.nextAttemptAt(BASE_MS, CAP_MS, 0.0, 3, NOW);
        long delayMs = next.toEpochMilli() - NOW.toEpochMilli();
        // base * 2^3 = 8000, jitter 0% → exactly 8000
        assertThat(delayMs).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("Next attempt is always in the future")
    void nextAttempt_alwaysAfterNow() {
        for (int i = 0; i < 10; i++) {
            Instant next = BackoffCalculator.nextAttemptAt(BASE_MS, CAP_MS, JITTER, i, NOW);
            assertThat(next).isAfter(NOW);
        }
    }

    @RepeatedTest(20)
    @DisplayName("Jitter bounds hold across repeated calls")
    void jitterBounds_consistent() {
        Instant next = BackoffCalculator.nextAttemptAt(BASE_MS, CAP_MS, JITTER, 1, NOW);
        long delayMs = next.toEpochMilli() - NOW.toEpochMilli();
        // base * 2^1 = 2000; max jitter 20% → at most 2400
        assertThat(delayMs).isBetween(2_000L, 2_400L);
    }
}
