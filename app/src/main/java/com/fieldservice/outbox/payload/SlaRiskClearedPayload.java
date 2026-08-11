package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Purpose-built payload record for the {@code SlaRiskCleared} domain event.
 */
public record SlaRiskClearedPayload(
        UUID workOrderId,
        UUID flagId,
        String flagType,
        String clearReason,
        Instant clearedAt
) {
    public static final String EVENT_TYPE = "SlaRiskCleared";
    public static final String AGGREGATE_TYPE = "WorkOrder";
}
