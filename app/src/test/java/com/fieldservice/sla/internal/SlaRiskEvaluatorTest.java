package com.fieldservice.sla.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fieldservice.sla.internal.SlaRiskEvaluator.RiskDecision;
import com.fieldservice.sla.internal.SlaRiskEvaluator.WorkOrderRiskSnapshot;

import static com.fieldservice.sla.internal.SlaRiskEvaluator.*;
import static com.fieldservice.sla.internal.SlaRiskEvaluator.RiskDecision.DecisionKind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SlaRiskEvaluator}.
 *
 * <p>No Spring context — pure unit tests with a paused {@link Clock}.
 */
class SlaRiskEvaluatorTest {

    static final Instant BASE = Instant.parse("2025-06-01T10:00:00Z");

    SlaRiskEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new SlaRiskEvaluator(Clock.fixed(BASE, ZoneOffset.UTC));
    }

    // ---- BR-13 verbatim fixture -------------------------------------------

    @Nested
    @DisplayName("BR-13: 240-minute resolution window, 0.80 at-risk fraction → flag at 192 min")
    class Br13Fixture {

        /**
         * Window = 240 min, fraction = 0.80 → atRiskAt = BASE + 192 min.
         * At exactly 192 minutes elapsed the work order must be flagged.
         */
        @Test
        @DisplayName("flags at exactly 192 minutes elapsed (not at 191)")
        void flagsAtExact192Min() {
            // atRiskAt = BASE + 192 min
            Instant atRiskAt     = BASE.plusSeconds(192 * 60);
            Instant resolutionDue = BASE.plusSeconds(240 * 60);

            // Evaluation at exactly atRiskAt
            Instant now = atRiskAt;
            WorkOrderRiskSnapshot snapshot = snapshot("NEW", BASE, resolutionDue, atRiskAt, 0, false);

            RiskDecision decision = evaluator.evaluate(snapshot, now);

            assertThat(decision.kind()).isEqualTo(DecisionKind.RAISE_FLAG);
            assertThat(decision.flagType()).isEqualTo(FLAG_AT_RISK);
            assertThat(decision.triggerReason()).isEqualTo(REASON_THRESHOLD);
        }

        @Test
        @DisplayName("does NOT flag at 191 minutes elapsed")
        void doesNotFlagAt191Min() {
            Instant atRiskAt     = BASE.plusSeconds(192 * 60);
            Instant resolutionDue = BASE.plusSeconds(240 * 60);

            // Evaluation at 191 minutes — one minute before threshold
            Instant now = BASE.plusSeconds(191 * 60);
            WorkOrderRiskSnapshot snapshot = snapshot("NEW", BASE, resolutionDue, atRiskAt, 0, false);

            RiskDecision decision = evaluator.evaluate(snapshot, now);

            assertThat(decision.kind()).isEqualTo(DecisionKind.HEALTHY);
        }
    }

    // ---- Projected overrun -------------------------------------------------

    @Nested
    @DisplayName("Projected overrun")
    class ProjectedOverrun {

        @Test
        @DisplayName("flags projected overrun even when at-risk threshold not breached")
        void flagsProjectedOverrun() {
            Instant atRiskAt      = BASE.plusSeconds(180 * 60); // threshold not yet reached
            Instant resolutionDue  = BASE.plusSeconds(240 * 60);
            Instant projectedCompletion = resolutionDue.plusSeconds(3600); // 1h overrun

            Instant now = BASE.plusSeconds(60 * 60); // only 60 min elapsed
            WorkOrderRiskSnapshot snapshot = new WorkOrderRiskSnapshot(
                    UUID.randomUUID(), "IN_PROGRESS", BASE,
                    resolutionDue, atRiskAt, 0, false,
                    projectedCompletion, "parts_delay");

            RiskDecision decision = evaluator.evaluate(snapshot, now);

            assertThat(decision.kind()).isEqualTo(DecisionKind.RAISE_FLAG);
            assertThat(decision.flagType()).isEqualTo(FLAG_PROJECTED_OVERRUN);
            assertThat(decision.triggerReason()).isEqualTo(REASON_PROJECTED_OVERRUN);
            assertThat(decision.projectionBasis()).isEqualTo("parts_delay");
        }
    }

    // ---- Paused clock suppression ------------------------------------------

    @Nested
    @DisplayName("Paused clock (ON_HOLD)")
    class PausedClock {

        @Test
        @DisplayName("does not flag while work order is paused")
        void noFlagWhilePaused() {
            // AT_RISK threshold already passed but the work order is ON_HOLD / paused
            Instant atRiskAt     = BASE.minusSeconds(10); // in the past
            Instant resolutionDue = BASE.plusSeconds(60 * 60);

            WorkOrderRiskSnapshot snapshot = snapshot("ON_HOLD", BASE, resolutionDue, atRiskAt, 0, true);

            RiskDecision decision = evaluator.evaluate(snapshot, BASE);

            assertThat(decision.kind()).isEqualTo(DecisionKind.HEALTHY);
        }

        @Test
        @DisplayName("effective deadline extended by cumulative hold minutes")
        void extendedDeadlineViaCumulativeHold() {
            // Hold of 60 min → effectiveDue = BASE + 240 + 60 = 300 min from BASE
            Instant atRiskAt     = BASE.plusSeconds(192 * 60 + 60 * 60); // threshold also pushed
            Instant resolutionDue = BASE.plusSeconds(240 * 60);
            int cumulativeHoldMin = 60;

            // Evaluate at BASE + 200 min — would be at-risk without hold extension,
            // but the effective due is extended to BASE + 300 min
            Instant now = BASE.plusSeconds(200 * 60);
            WorkOrderRiskSnapshot snapshot = new WorkOrderRiskSnapshot(
                    UUID.randomUUID(), "IN_PROGRESS", BASE,
                    resolutionDue, atRiskAt, cumulativeHoldMin, false,
                    null, null);

            RiskDecision decision = evaluator.evaluate(snapshot, now);

            // Not yet at atRiskAt (which accounts for the hold)
            assertThat(decision.kind()).isEqualTo(DecisionKind.HEALTHY);
        }
    }

    // ---- Terminal state clear ----------------------------------------------

    @Nested
    @DisplayName("Terminal state")
    class TerminalState {

        @Test
        @DisplayName("COMPLETED returns CLEAR_ALL with terminal_state reason")
        void completedClearsFlags() {
            WorkOrderRiskSnapshot snapshot = snapshot("COMPLETED", BASE,
                    BASE.plusSeconds(3600), BASE.plusSeconds(1800), 0, false);

            RiskDecision decision = evaluator.evaluate(snapshot, BASE);

            assertThat(decision.kind()).isEqualTo(DecisionKind.CLEAR_ALL);
            assertThat(decision.clearReason()).isEqualTo(REASON_TERMINAL_STATE);
        }

        @Test
        @DisplayName("CANCELLED returns CLEAR_ALL")
        void cancelledClearsFlags() {
            WorkOrderRiskSnapshot snapshot = snapshot("CANCELLED", BASE,
                    BASE.plusSeconds(3600), BASE.plusSeconds(1800), 0, false);

            RiskDecision decision = evaluator.evaluate(snapshot, BASE);

            assertThat(decision.kind()).isEqualTo(DecisionKind.CLEAR_ALL);
        }
    }

    // ---- No deadline (no SLA policy) ---------------------------------------

    @Test
    @DisplayName("healthy when no deadline set (SLA policy not applied)")
    void noDeadlineIsHealthy() {
        WorkOrderRiskSnapshot snapshot = snapshot("NEW", BASE, null, null, 0, false);

        RiskDecision decision = evaluator.evaluate(snapshot, BASE.plusSeconds(99_999));

        assertThat(decision.kind()).isEqualTo(DecisionKind.HEALTHY);
    }

    // ---- Threshold already passed ------------------------------------------

    @Test
    @DisplayName("flags immediately with threshold_already_passed when deadline is in the past")
    void thresholdAlreadyPassed() {
        Instant atRiskAt     = BASE.minusSeconds(60 * 60); // 1h in the past
        Instant resolutionDue = BASE.minusSeconds(30 * 60); // also past (backdated import)

        WorkOrderRiskSnapshot snapshot = snapshot("NEW", BASE.minusSeconds(360 * 60),
                resolutionDue, atRiskAt, 0, false);

        RiskDecision decision = evaluator.evaluate(snapshot, BASE);

        assertThat(decision.kind()).isEqualTo(DecisionKind.RAISE_FLAG);
        assertThat(decision.flagType()).isEqualTo(FLAG_AT_RISK);
        assertThat(decision.triggerReason()).isEqualTo(REASON_THRESHOLD_PASSED);
    }

    // ---- Helpers -----------------------------------------------------------

    private static WorkOrderRiskSnapshot snapshot(String state, Instant createdAt,
                                                   Instant resolutionDue, Instant atRiskAt,
                                                   int cumulativeHoldMin, boolean paused) {
        return new WorkOrderRiskSnapshot(
                UUID.randomUUID(), state, createdAt,
                resolutionDue, atRiskAt, cumulativeHoldMin, paused,
                null, null);
    }
}
