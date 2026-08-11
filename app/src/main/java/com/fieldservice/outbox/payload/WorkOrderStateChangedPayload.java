package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Purpose-built payload record for the {@code WorkOrderStateChanged} event type.
 *
 * <p>This record is the <em>allow-list</em> of fields that may appear in a
 * {@code WorkOrderStateChanged} event payload. Serialising the {@code WorkOrder} entity
 * directly is explicitly forbidden — a future addition of a PII field to the entity would
 * silently leak that data to all downstream consumers.
 *
 * <p>No field here carries {@link com.fieldservice.platform.outbox.annotation.Restricted} data.
 * The work order ID, state names, priority, and timestamps are all Internal-classified.
 */
public record WorkOrderStateChangedPayload(
        UUID workOrderId,
        String fromState,
        String toState,
        String priority,
        Instant transitionedAt
) {
    /** Stable event type identifier used in {@link com.fieldservice.platform.api.DomainEvent}. */
    public static final String EVENT_TYPE = "WorkOrderStateChanged";

    /** Stable aggregate type identifier. */
    public static final String AGGREGATE_TYPE = "WorkOrder";
}
