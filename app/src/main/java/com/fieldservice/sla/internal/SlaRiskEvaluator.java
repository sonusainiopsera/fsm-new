package com.fieldservice.sla.internal;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure SLA risk evaluation function.
 *
 * <p><strong>Design contract:</strong>
 * <ul>
 *   <li>No I/O — performs zero database reads or writes.</li>
 *   <li>No clock lookups — the evaluation instant is always passed in by the caller
 *       so behaviour is fully deterministic in tests with a fixed Clock.</li>
 *   <li>Single responsibility — decides flag type and reason only; persistence is
 *       the caller's concern.</li>
 * </ul>
 *
 * <p>BR-13 verbatim fixture: a work order with a 240-minute resolution window and
 * an at-risk fraction of 0.80 is flagged at exactly 192 minutes elapsed (the stored
 * {@code atRiskAt} instant) and is not flagged at 191 minutes.
 */
@Component
class SlaRiskEvaluator {

    /**
     * Evaluates the risk position of a single work order.
     *
     * @param snapshot the pre-built snapshot with effective deadlines and open-flag state
     * @param now      the evaluation instant (from the caller's injected Clock)
     * @return a {@link RiskDecision} describing what flag action (if any) is needed
     */
    RiskDecision evaluate(WorkOrderRiskSnapshot snapshot, Instant now) {
        // Terminal states: clear any open flag and take no further action.
        if (isTerminal(snapshot.state())) {
            if (snapshot.existingOpenFlagType() != null) {
                return new RiskDecision.Healthy("terminal_state");
            }
            return new RiskDecision.Healthy(null);
        }

        // SLA clock is paused: effective deadlines are still accumulating; do not flag.
        if (snapshot.currentlyPaused()) {
            return new RiskDecision.Paused();
        }

        // Guard: work order has no deadline stamped (pre-policy or legacy row).
        if (snapshot.effectiveResolutionDueAt() == null) {
            return new RiskDecision.Healthy(null);
        }

        // PROJECTED_OVERRUN: now is at or past the effective resolution deadline.
        if (!now.isBefore(snapshot.effectiveResolutionDueAt())) {
            long minutesPast = Duration.between(snapshot.effectiveResolutionDueAt(), now).toMinutes();
            String triggerReason = snapshot.existingOpenFlagType() == null
                    ? "resolution_deadline_passed"
                    : "resolution_deadline_still_passed";
            String projectionBasis = "effectiveResolutionDueAt=" + snapshot.effectiveResolutionDueAt()
                    + " now=" + now;
            return new RiskDecision.ProjectedOverrun(
                    triggerReason,
                    projectionBasis,
                    -(int) minutesPast   // negative = overrun minutes
            );
        }

        // AT_RISK: now is at or past the effective at-risk threshold.
        if (snapshot.effectiveAtRiskAt() != null && !now.isBefore(snapshot.effectiveAtRiskAt())) {
            long minutesRemaining = Duration.between(now, snapshot.effectiveResolutionDueAt()).toMinutes();
            String triggerReason = "at_risk_threshold_elapsed";
            String projectionBasis = "effectiveAtRiskAt=" + snapshot.effectiveAtRiskAt()
                    + " effectiveResolutionDueAt=" + snapshot.effectiveResolutionDueAt()
                    + " now=" + now;
            // Idempotent: AT_RISK is already open for this work order — no new flag needed.
            if ("AT_RISK".equals(snapshot.existingOpenFlagType())) {
                return new RiskDecision.Healthy(null);
            }
            return new RiskDecision.AtRisk(triggerReason, projectionBasis, (int) minutesRemaining);
        }

        // Work order is healthy: if there is an open flag, clear it.
        if (snapshot.existingOpenFlagType() != null) {
            return new RiskDecision.Healthy("returned_healthy");
        }

        return new RiskDecision.Healthy(null);
    }

    /**
     * Detects breach conditions for both response and resolution deadlines.
     *
     * <p>Returns an empty list when: the work order is terminal, the SLA clock is paused,
     * or no effective deadlines are set. May return two entries when both response and
     * resolution deadlines have passed simultaneously.
     *
     * @param snapshot the pre-built snapshot with effective deadlines
     * @param now      the evaluation instant (from the caller's injected Clock)
     * @return list of {@link RiskDecision.Breached} decisions, one per breached deadline
     */
    List<RiskDecision.Breached> evaluateBreaches(WorkOrderRiskSnapshot snapshot, Instant now) {
        if (isTerminal(snapshot.state()) || snapshot.currentlyPaused()) {
            return List.of();
        }

        List<RiskDecision.Breached> breaches = new ArrayList<>(2);

        // RESOLUTION breach: resolution deadline has passed.
        if (snapshot.effectiveResolutionDueAt() != null
                && !now.isBefore(snapshot.effectiveResolutionDueAt())) {
            long overrun = Duration.between(snapshot.effectiveResolutionDueAt(), now).toMinutes();
            breaches.add(new RiskDecision.Breached(
                    "RESOLUTION", snapshot.effectiveResolutionDueAt(), (int) overrun));
        }

        // RESPONSE breach: response deadline has passed and the work order has not yet
        // transitioned to a responding state (EN_ROUTE or IN_PROGRESS).
        if (snapshot.effectiveResponseDueAt() != null
                && !now.isBefore(snapshot.effectiveResponseDueAt())
                && isPreResponseState(snapshot.state())) {
            long overrun = Duration.between(snapshot.effectiveResponseDueAt(), now).toMinutes();
            breaches.add(new RiskDecision.Breached(
                    "RESPONSE", snapshot.effectiveResponseDueAt(), (int) overrun));
        }

        return breaches;
    }

    private static boolean isTerminal(WorkOrderState state) {
        return state == WorkOrderState.COMPLETED
                || state == WorkOrderState.CLOSED
                || state == WorkOrderState.CANCELLED;
    }

    private static boolean isPreResponseState(WorkOrderState state) {
        return state == WorkOrderState.OPEN || state == WorkOrderState.ASSIGNED;
    }
}
