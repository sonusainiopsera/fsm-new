package com.fieldservice.sla.internal;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, clock-injected SLA risk evaluator.
 *
 * <p>Takes an immutable {@link WorkOrderRiskSnapshot} and the current evaluation instant;
 * returns a {@link RiskDecision} describing what flag action (if any) should be taken,
 * and a list of {@link BreachDecision}s for any passed deadlines.
 * Performs no I/O, no repository access, and no side effects — all behaviour is
 * unit-testable without a database.
 *
 * <p>Flag types and trigger reasons:
 * <ul>
 *   <li>{@code AT_RISK} — elapsed time ≥ at-risk threshold (BR-13)</li>
 *   <li>{@code PROJECTED_OVERRUN} — projected completion exceeds effective resolution deadline</li>
 * </ul>
 *
 * <p>Breach types:
 * <ul>
 *   <li>{@code RESPONSE}   — effective response deadline has passed</li>
 *   <li>{@code RESOLUTION} — effective resolution deadline has passed</li>
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

    static final String BREACH_RESPONSE   = "RESPONSE";
    static final String BREACH_RESOLUTION = "RESOLUTION";

    private final Clock clock;

    SlaRiskEvaluator(Clock clock) {
        this.clock = clock;
    }

    /**
     * Evaluates risk flags for a single work order snapshot at the given evaluation instant.
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

    /**
     * Evaluates breach conditions for a single work order snapshot.
     *
     * <p>Returns 0–2 {@link BreachDecision}s — one for each deadline type that has
     * been exceeded. Callers (the scheduler) route each breach to
     * {@link SlaBreachService#recordBreach} within a transaction; recording is idempotent
     * so repeated evaluation produces no duplicate rows.
     *
     * <p>Always returns an empty list for terminal or paused work orders.
     *
     * @param snapshot immutable snapshot of work order deadline data
     * @param now      the evaluation instant
     * @return list of breaches detected (may be empty, one, or two elements)
     */
    List<BreachDecision> evaluateBreaches(WorkOrderRiskSnapshot snapshot, Instant now) {
        // Terminal or paused: no new breaches
        if (snapshot.isTerminal() || snapshot.isPaused()) {
            return List.of();
        }

        List<BreachDecision> result = new ArrayList<>(2);

        // Check response deadline breach
        Instant effectiveResponse = snapshot.effectiveResponseDue();
        if (effectiveResponse != null && now.isAfter(effectiveResponse)) {
            long overrun = Math.max(0, Duration.between(effectiveResponse, now).toMinutes());
            result.add(new BreachDecision(BREACH_RESPONSE, effectiveResponse, overrun,
                    snapshot.cumulativeHoldMinutes()));
        }

        // Check resolution deadline breach
        Instant effectiveResolution = snapshot.effectiveResolutionDue();
        if (!Instant.MAX.equals(effectiveResolution) && now.isAfter(effectiveResolution)) {
            long overrun = Math.max(0, Duration.between(effectiveResolution, now).toMinutes());
            result.add(new BreachDecision(BREACH_RESOLUTION, effectiveResolution, overrun,
                    snapshot.cumulativeHoldMinutes()));
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Immutable snapshot of the data needed to evaluate one work order's risk and breach.
     * Built by the scheduler from a lightweight projection query — no full entity load.
     */
    record WorkOrderRiskSnapshot(
            java.util.UUID workOrderId,
            String         state,
            Instant        createdAt,
            Instant        resolutionDue,
            Instant        responseDeadline,
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

        /** Effective response deadline adjusted for total pause time. Null if no response deadline. */
        Instant effectiveResponseDue() {
            if (responseDeadline == null) return null;
            return responseDeadline.plus(Duration.ofMinutes(cumulativeHoldMinutes));
        }
    }

    /** Decision returned by the flag evaluator — no I/O performed. */
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

    /** Breach outcome from {@link #evaluateBreaches} — represents one passed deadline. */
    record BreachDecision(
            String  breachType,
            Instant effectiveDeadline,
            long    overrunMinutes,
            long    pausedMinutesExcluded
    ) {}
}
