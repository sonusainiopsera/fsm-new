package com.fieldservice.dispatch.internal;

import java.util.UUID;

/**
 * Outbox event payload for the TECHNICIAN_ASSIGNMENT_REVOKED domain event.
 *
 * <p>Published atomically with the TECHNICIAN_ASSIGNED event for the incoming technician
 * when a reassignment supersedes an existing assignment.
 *
 * @param workOrderId          the reassigned work order
 * @param revokedTechnicianId  the technician whose assignment was ended
 * @param supersededAssignmentId  the assignment row that was end-dated
 * @param newAssignmentId      the new assignment that supersedes it
 * @param reassignmentReason   reason code for the reassignment
 */
public record TechnicianRevokedPayload(
        UUID   workOrderId,
        UUID   revokedTechnicianId,
        UUID   supersededAssignmentId,
        UUID   newAssignmentId,
        String reassignmentReason
) {}
