package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload for the {@code WorkOrderDuplicateLinked} outbox event published when a work order
 * is linked to an existing duplicate (WO-196).
 *
 * <p>Carries only identifiers — no PII. The notification worker resolves customer
 * contact details independently.
 */
public record WorkOrderDuplicateLinkedPayload(
        UUID workOrderId,
        UUID canonicalWorkOrderId,
        UUID customerId,
        String workOrderReference,
        String canonicalWorkOrderReference,
        Instant linkedAt
) {
    public static final String EVENT_TYPE     = "WorkOrderDuplicateLinked";
    public static final String AGGREGATE_TYPE = "WorkOrder";
}
