package com.fieldservice.sla.internal;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link SlaRiskEvaluator#evaluateBreaches}.
 *
 * <p>No Spring context — fixed clock, deterministic assertions.
 */
class SlaBreachEvaluatorTest {

    private static final Instant NOW              = Instant.parse("2026-08-17T10:00:00Z");
    private static final Instant RESOLUTION_DUE   = Instant.parse("2026-08-17T09:00:00Z"); // 60m past
    private static final Instant RESPONSE_DUE     = Instant.parse("2026-08-17T08:30:00Z"); // 90m past
    private static final Instant FUTURE_DEADLINE  = Instant.parse("2026-08-17T11:00:00Z"); // future

    private final SlaRiskEvaluator evaluator = new SlaRiskEvaluator();

    // ── Helper ─────────────────────────────────────────────────────────────────

    private WorkOrderRiskSnapshot snapshot(WorkOrderState state,
                                            Instant effectiveResolutionDueAt,
                                            Instant effectiveResponseDueAt,
                                            boolean paused) {
        return new WorkOrderRiskSnapshot(
                UUID.randomUUID(), state, "CRITICAL",
                Instant.parse("2026-08-17T07:00:00Z"),
                effectiveResponseDueAt,
                null, effectiveResolutionDueAt,
                effectiveResponseDueAt,
                paused, null);
    }

    // ── No breach scenarios ────────────────────────────────────────────────────

    @Test
    void terminalState_returnsEmpty() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.CLOSED, RESOLUTION_DUE, RESPONSE_DUE, false);
        assertThat(evaluator.evaluateBreaches(s, NOW)).isEmpty();
    }

    @Test
    void paused_returnsEmpty() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.ASSIGNED, RESOLUTION_DUE, RESPONSE_DUE, true);
        assertThat(evaluator.evaluateBreaches(s, NOW)).isEmpty();
    }

    @Test
    void noDeadlines_returnsEmpty() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.ASSIGNED, null, null, false);
        assertThat(evaluator.evaluateBreaches(s, NOW)).isEmpty();
    }

    @Test
    void futureResolutionDeadline_noResolutionBreach() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.ASSIGNED, FUTURE_DEADLINE, null, false);
        assertThat(evaluator.evaluateBreaches(s, NOW)).isEmpty();
    }

    @Test
    void futureResponseDeadline_noResponseBreach() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.ASSIGNED, null, FUTURE_DEADLINE, false);
        assertThat(evaluator.evaluateBreaches(s, NOW)).isEmpty();
    }

    // ── Resolution breach ──────────────────────────────────────────────────────

    @Test
    void pastResolutionDeadline_returnsResolutionBreach() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.IN_PROGRESS, RESOLUTION_DUE, null, false);
        List<RiskDecision.Breached> breaches = evaluator.evaluateBreaches(s, NOW);

        assertThat(breaches).hasSize(1);
        RiskDecision.Breached b = breaches.get(0);
        assertThat(b.breachType()).isEqualTo("RESOLUTION");
        assertThat(b.effectiveDeadline()).isEqualTo(RESOLUTION_DUE);
        assertThat(b.overrunMinutes()).isEqualTo(60);
    }

    @Test
    void resolutionDeadlineAtExactNow_overrunIsZero() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.IN_PROGRESS, NOW, null, false);
        List<RiskDecision.Breached> breaches = evaluator.evaluateBreaches(s, NOW);

        assertThat(breaches).hasSize(1);
        assertThat(breaches.get(0).overrunMinutes()).isEqualTo(0);
    }

    @Test
    void enRouteState_resolutionBreachDetected() {
        // Response already met (EN_ROUTE), but resolution missed
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.EN_ROUTE, RESOLUTION_DUE, RESPONSE_DUE, false);
        List<RiskDecision.Breached> breaches = evaluator.evaluateBreaches(s, NOW);

        // Only RESOLUTION breach — EN_ROUTE is not a pre-response state
        assertThat(breaches).extracting(RiskDecision.Breached::breachType).containsExactly("RESOLUTION");
    }

    // ── Response breach ────────────────────────────────────────────────────────

    @Test
    void assignedState_pastResponseDeadline_returnsResponseBreach() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.ASSIGNED, null, RESPONSE_DUE, false);
        List<RiskDecision.Breached> breaches = evaluator.evaluateBreaches(s, NOW);

        assertThat(breaches).hasSize(1);
        assertThat(breaches.get(0).breachType()).isEqualTo("RESPONSE");
        assertThat(breaches.get(0).overrunMinutes()).isEqualTo(90);
    }

    @Test
    void openState_pastResponseDeadline_returnsResponseBreach() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.OPEN, null, RESPONSE_DUE, false);
        List<RiskDecision.Breached> breaches = evaluator.evaluateBreaches(s, NOW);

        assertThat(breaches).hasSize(1);
        assertThat(breaches.get(0).breachType()).isEqualTo("RESPONSE");
    }

    @Test
    void inProgressState_pastResponseDeadline_noResponseBreach() {
        // Already responding — response breach not applicable
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.IN_PROGRESS, null, RESPONSE_DUE, false);
        assertThat(evaluator.evaluateBreaches(s, NOW)).isEmpty();
    }

    // ── Both deadlines breached simultaneously ─────────────────────────────────

    @Test
    void bothDeadlinesPassed_inAssignedState_returnsTwoBreach() {
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.ASSIGNED, RESOLUTION_DUE, RESPONSE_DUE, false);
        List<RiskDecision.Breached> breaches = evaluator.evaluateBreaches(s, NOW);

        assertThat(breaches).hasSize(2);
        assertThat(breaches).extracting(RiskDecision.Breached::breachType)
                .containsExactlyInAnyOrder("RESOLUTION", "RESPONSE");
    }

    @Test
    void resolutionBreachOverrunMatchesElapsed() {
        // Deadline at 09:30, now 10:00 → 30 minutes overrun
        Instant deadline = Instant.parse("2026-08-17T09:30:00Z");
        WorkOrderRiskSnapshot s = snapshot(WorkOrderState.IN_PROGRESS, deadline, null, false);
        List<RiskDecision.Breached> breaches = evaluator.evaluateBreaches(s, NOW);

        assertThat(breaches.get(0).overrunMinutes()).isEqualTo(30);
    }
}
