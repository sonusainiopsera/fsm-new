package com.fieldservice.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * Public port for finalising SLA breach overrun at work order closure.
 *
 * <p>Implemented by the internal {@code SlaBreachService}. Called by the
 * work order lifecycle transition handler when the work order reaches a
 * terminal state (COMPLETED, CLOSED, or CANCELLED).
 */
public interface SlaBreachPort {

    /**
     * Writes {@code final_overrun_minutes} for all open breach records belonging
     * to {@code workOrderId}. Idempotent — if {@code final_overrun_minutes} is
     * already set the method returns without overwriting it.
     *
     * @param workOrderId     the work order reaching a terminal state
     * @param terminalInstant the instant at which the terminal transition was applied
     */
    void finalise(UUID workOrderId, Instant terminalInstant);
}
