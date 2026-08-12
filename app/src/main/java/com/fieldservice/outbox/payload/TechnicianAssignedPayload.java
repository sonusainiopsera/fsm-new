package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload for the {@code TechnicianAssigned} outbox event (WO-138).
 *
 * <p>Carries only identifiers and operational state — no PII beyond IDs.
 * The notification worker resolves the technician's contact details independently.
 */
public record TechnicianAssignedPayload(
        UUID workOrderId,
        UUID assignmentId,
        UUID technicianId,
        UUID assignedBy,
        Instant assignedAt,
        String workOrderPriority,
        String workOrderReference
) {
    public static final String EVENT_TYPE     = "TechnicianAssigned";
    public static final String AGGREGATE_TYPE = "Assignment";
}
