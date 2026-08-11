package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox payload for the {@code WorkOrderCreated} domain event.
 *
 * <p>Contains only non-PII fields. Deadlines are included so downstream risk
 * projection and compliance reporting can consume the initial commitment without
 * querying the work order table.
 */
public record WorkOrderCreatedPayload(
        UUID workOrderId,
        String priority,
        UUID customerId,
        UUID siteId,
        Instant responseDueAt,
        Instant resolutionDueAt,
        Instant atRiskAt,
        Instant createdAt
) {
    public static final String EVENT_TYPE = "WorkOrderCreated";
    public static final String AGGREGATE_TYPE = "WorkOrder";
}
