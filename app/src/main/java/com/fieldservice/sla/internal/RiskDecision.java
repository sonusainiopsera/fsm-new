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
 *   <li>{@link Breached} — a deadline has passed; route to SlaBreachService.</li>
 * </ul>
 */
sealed interface RiskDecision permits
        RiskDecision.Healthy,
        RiskDecision.AtRisk,
        RiskDecision.ProjectedOverrun,
        RiskDecision.Paused,
        RiskDecision.Breached {

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

    /**
     * A response or resolution deadline has passed. The scheduler routes this
     * outcome to {@code SlaBreachService} rather than the flag writer.
     *
     * @param breachType            "RESPONSE" or "RESOLUTION"
     * @param effectiveDeadline     the pause-adjusted deadline that was missed
     * @param overrunMinutes        minutes past the deadline at detection (raw, may be clamped by service)
     */
    record Breached(
            String breachType,
            java.time.Instant effectiveDeadline,
            int overrunMinutes
    ) implements RiskDecision {}
}
