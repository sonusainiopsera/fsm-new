package com.fieldservice.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * Public interface for computing SLA deadlines.
 * All methods take an injected Clock instant so tests can use a fixed time source.
 */
public interface SlaDeadlineCalculator {

    /**
     * Computes response, resolution and at-risk deadlines for a new work order.
     * Throws {@link SlaPolicyUnavailableException} if no active policy exists.
     *
     * @param priority   the work order priority (LOW, MEDIUM, HIGH, CRITICAL)
     * @param createdAt  the instant the work order was created
     * @return deadline result with nominal (non-pause-adjusted) deadlines
     */
    SlaDeadlineResult calculate(String priority, Instant createdAt);

    /**
     * Returns the effective resolution deadline for an existing work order, adjusted
     * for accrued SLA clock-pause time. An open pause row accrues up to {@code evaluatedAt}.
     *
     * @param workOrderId      identifier of the work order
     * @param nominalDeadline  the stored resolution_deadline
     * @param evaluatedAt      current instant (or the instant of evaluation)
     * @return effective resolution deadline = nominalDeadline + total paused duration
     */
    Instant effectiveResolutionDeadline(UUID workOrderId, Instant nominalDeadline, Instant evaluatedAt);
}
