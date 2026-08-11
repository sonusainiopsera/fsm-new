package com.fieldservice.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * Public port for computing SLA deadlines and effective resolution timestamps.
 *
 * <p>All calculations use an injected {@link java.time.Clock} so tests can control time.
 */
public interface SlaDeadlineCalculator {

    /**
     * Computes response, resolution, and at-risk deadlines for a work order
     * at the moment of creation.
     *
     * @param priority  the work order priority (must match a {@link SlaPolicy} row)
     * @param createdAt the instant at which the work order was created
     * @return computed deadlines
     * @throws SlaPolicyUnavailableException if no active policy resolves for the priority
     */
    SlaDeadlines calculate(String priority, Instant createdAt);

    /**
     * Returns the effective resolution deadline, extending {@code resolutionDueAt} by
     * the total accumulated pause duration from SLA-clock-pausing hold intervals.
     *
     * <p>Open pause rows accrue duration up to {@code now} (from the injected Clock).
     *
     * @param workOrderId     the work order whose pause ledger is consulted
     * @param resolutionDueAt the original stamped resolution deadline
     * @return effective resolution deadline accounting for all pauses
     */
    Instant effectiveResolutionDueAt(UUID workOrderId, Instant resolutionDueAt);
}
