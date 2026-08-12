package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Purpose-built payload record for the {@code SlaBreached} domain event.
 *
 * <p>No field carries Restricted-classified data. All fields are Internal tier.
 */
public record SlaBreachedPayload(
        UUID workOrderId,
        UUID breachId,
        String breachType,
        Instant effectiveDeadline,
        Instant detectedAt,
        int overrunMinutes,
        int pausedMinutesExcluded
) {
    public static final String EVENT_TYPE     = "SlaBreached";
    public static final String AGGREGATE_TYPE = "WorkOrder";
}
