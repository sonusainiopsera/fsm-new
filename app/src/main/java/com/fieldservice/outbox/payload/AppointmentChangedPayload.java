package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload for the {@code AppointmentChanged} outbox event published when a confirmed
 * appointment window on a work order is changed (BR-05).
 *
 * <p>Carries only identifiers and timestamps — no PII. The notification worker
 * resolves customer contact details independently.
 */
public record AppointmentChangedPayload(
        UUID workOrderId,
        UUID customerId,
        String workOrderReference,
        Instant previousWindowStart,
        Instant previousWindowEnd,
        Instant newWindowStart,
        Instant newWindowEnd,
        String changeReason,
        Instant changedAt
) {
    public static final String EVENT_TYPE     = "AppointmentChanged";
    public static final String AGGREGATE_TYPE = "WorkOrder";
}
