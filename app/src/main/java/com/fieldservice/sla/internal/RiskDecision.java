package com.fieldservice.sla.internal;

/**
 * Sealed result type for a single work order SLA risk evaluation.
 *
 * <p>The scheduler pattern-matches on the concrete type to decide what to persist:
 * <ul>
 *   <li>{@link Healthy} — no change needed; clear any existing open flag.</li>
 *   <li>{@link AtRisk} — raise or confirm an {@code AT_RISK} flag.</li>
 *   <li>{@link ProjectedOverrun} — raise or confirm a {@code PROJECTED_OVERRUN} flag.</li>
 *   <li>{@link Paused} — SLA clock is currently suspended; no flag changes made.</li>
 * </ul>
 */
sealed interface RiskDecision permits
        RiskDecision.Healthy,
        RiskDecision.AtRisk,
        RiskDecision.ProjectedOverrun,
        RiskDecision.Paused {

    /** No flag needed. If an open flag exists the scheduler will clear it. */
    record Healthy(String clearReason) implements RiskDecision {}

    /** Work order has passed the at-risk fraction threshold. */
    record AtRisk(
            String triggerReason,
            String projectionBasis,
            int minutesRemaining
    ) implements RiskDecision {}

    /** Work order has passed or will definitely miss the resolution deadline. */
    record ProjectedOverrun(
            String triggerReason,
            String projectionBasis,
            int minutesRemaining
    ) implements RiskDecision {}

    /** SLA clock is paused; defer evaluation until the work order resumes. */
    record Paused() implements RiskDecision {}
}
