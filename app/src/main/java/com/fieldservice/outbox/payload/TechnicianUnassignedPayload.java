package com.fieldservice.outbox.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload for the {@code TechnicianUnassigned} outbox event published when a technician
 * is reassigned off a work order (WO-139).
 *
 * <p>Carries only identifiers and operational state — no PII beyond IDs.
 */
public record TechnicianUnassignedPayload(
        UUID workOrderId,
        UUID supersededAssignmentId,
        UUID outgoingTechnicianId,
        UUID reassignedBy,
        Instant unassignedAt,
        String reassignmentReason,
        String workOrderPriority,
        String workOrderReference
) {
    public static final String EVENT_TYPE     = "TechnicianUnassigned";
    public static final String AGGREGATE_TYPE = "Assignment";
}
