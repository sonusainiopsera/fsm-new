package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Purpose-built payload record for the {@code SlaRiskFlagged} domain event.
 *
 * <p>No field carries Restricted-classified data. All fields are Internal tier.
 */
public record SlaRiskFlaggedPayload(
        UUID workOrderId,
        UUID flagId,
        String flagType,
        String triggerReason,
        String projectionBasis,
        Integer minutesRemaining,
        Instant raisedAt
) {
    public static final String EVENT_TYPE = "SlaRiskFlagged";
    public static final String AGGREGATE_TYPE = "WorkOrder";
}
