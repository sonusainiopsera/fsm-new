package com.fieldservice.dispatch.web;

import java.time.Instant;
import java.util.UUID;

/**
 * Response envelope for a successful work order reassignment.
 */
public record ReassignmentResponse(ReassignmentData data, Meta meta) {

    public record ReassignmentData(
            UUID    assignmentId,
            UUID    supersededAssignmentId,
            UUID    workOrderId,
            UUID    technicianId,
            String  state,
            String  reassignmentReason,
            boolean appointmentImpactRecorded,
            Instant assignedAt
    ) {}

    public record Meta(String traceId) {}
}
