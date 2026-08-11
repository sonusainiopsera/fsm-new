package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Purpose-built payload record for the {@code PartsConsumed} event type.
 */
public record PartsConsumedPayload(
        UUID workOrderId,
        UUID locationId,
        List<LineItem> lines,
        UUID actorUserId,
        Instant occurredAt
) {
    public static final String EVENT_TYPE = "PartsConsumed";
    public static final String AGGREGATE_TYPE = "WorkOrder";

    public record LineItem(UUID partId, int quantity, int resultingQuantityOnHand) {}
}
