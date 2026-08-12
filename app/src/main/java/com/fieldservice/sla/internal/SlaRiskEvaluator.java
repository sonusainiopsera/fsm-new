package com.fieldservice.sla.internal;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Pure, clock-injected SLA risk evaluator.
 *
 * <p>Takes an immutable {@link WorkOrderRiskSnapshot} and the current evaluation instant;
 * returns a {@link RiskDecision} describing what action (if any) should be taken.
 * Performs no I/O, no repository access, and no side effects — all boundary behaviour
 * is unit-testable without a database.
 *
 * <p>Flag types and trigger reasons:
 * <ul>
 *   <li>{@code AT_RISK} — elapsed time ≥ at-risk threshold (BR-13)</li>
 *   <li>{@code PROJECTED_OVERRUN} — projected completion exceeds effective resolution deadline</li>
 * </ul>
 */
@Component
class SlaRiskEvaluator {

    static final String FLAG_AT_RISK          = "AT_RISK";
    static final String FLAG_PROJECTED_OVERRUN = "PROJECTED_OVERRUN";

    static final String REASON_THRESHOLD        = "threshold_elapsed";
    static final String REASON_THRESHOLD_PASSED = "threshold_already_passed";
    static final String REASON_PROJECTED_OVERRUN = "projected_overrun";
    static final String REASON_TERMINAL_STATE   = "terminal_state";

    private final Clock clock;

    SlaRiskEvaluator(Clock clock) {
        this.clock = clock;
    }

    /**
     * Evaluates risk for a single work order snapshot at the given evaluation instant.
     *
     * @param snapshot immutable snapshot of work order deadline data
     * @param now      the evaluation instant (from scheduler; inject Clock in tests)
     * @return decision describing what flag to raise/clear, or HEALTHY
     */
    RiskDecision evaluate(WorkOrderRiskSnapshot snapshot, Instant now) {
        // Terminal states: clear any open flag
        if (snapshot.isTerminal()) {
            return RiskDecision.clearAll(REASON_TERMINAL_STATE);
        }

        // If paused, extend evaluation; no new flag while on hold
        if (snapshot.isPaused()) {
            return RiskDecision.healthy();
        }

        Instant effectiveDue = snapshot.effectiveResolutionDue();
        long minutesRemaining = Duration.between(now, effectiveDue).toMinutes();

        // Check projected overrun first (higher severity)
        if (snapshot.projectedCompletion() != null && snapshot.projectedCompletion().isAfter(effectiveDue)) {
            return RiskDecision.flag(
                    FLAG_PROJECTED_OVERRUN,
                    REASON_PROJECTED_OVERRUN,
                    snapshot.projectionBasis(),
                    (int) minutesRemaining);
        }

        // Check AT_RISK threshold (BR-13: flag when elapsed >= atRiskFraction * resolutionWindow)
        if (snapshot.atRiskAt() != null && !now.isBefore(snapshot.atRiskAt())) {
            String reason = effectiveDue.isBefore(now) ? REASON_THRESHOLD_PASSED : REASON_THRESHOLD;
            return RiskDecision.flag(FLAG_AT_RISK, reason, null, (int) minutesRemaining);
        }

        return RiskDecision.healthy();
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of the data needed to evaluate one work order's risk.
     * Built by the scheduler from a lightweight projection query — no full entity load.
     */
    record WorkOrderRiskSnapshot(
            java.util.UUID workOrderId,
            String         state,
            Instant        createdAt,
            Instant        resolutionDue,
            Instant        atRiskAt,
            int            cumulativeHoldMinutes,
            boolean        isCurrentlyPaused,
            Instant        projectedCompletion,
            String         projectionBasis
    ) {
        boolean isTerminal() {
            return "COMPLETED".equals(state) || "CLOSED".equals(state) || "CANCELLED".equals(state);
        }

        boolean isPaused() {
            return isCurrentlyPaused;
        }

        /** Effective resolution deadline adjusted for total pause time. */
        Instant effectiveResolutionDue() {
            if (resolutionDue == null) return Instant.MAX;
            return resolutionDue.plus(Duration.ofMinutes(cumulativeHoldMinutes));
        }
    }

    /** Decision returned by the evaluator — no I/O performed. */
    record RiskDecision(
            DecisionKind kind,
            String       flagType,
            String       triggerReason,
            String       projectionBasis,
            Integer      minutesRemaining,
            String       clearReason
    ) {
        enum DecisionKind { RAISE_FLAG, CLEAR_ALL, HEALTHY }

        static RiskDecision healthy() {
            return new RiskDecision(DecisionKind.HEALTHY, null, null, null, null, null);
        }

        static RiskDecision flag(String flagType, String triggerReason,
                                  String projectionBasis, int minutesRemaining) {
            return new RiskDecision(DecisionKind.RAISE_FLAG, flagType, triggerReason,
                    projectionBasis, minutesRemaining, null);
        }

        static RiskDecision clearAll(String clearReason) {
            return new RiskDecision(DecisionKind.CLEAR_ALL, null, null, null, null, clearReason);
        }

        boolean shouldRaise() { return kind == DecisionKind.RAISE_FLAG; }
        boolean shouldClear() { return kind == DecisionKind.CLEAR_ALL; }
        boolean isHealthy()   { return kind == DecisionKind.HEALTHY; }
    }
}
