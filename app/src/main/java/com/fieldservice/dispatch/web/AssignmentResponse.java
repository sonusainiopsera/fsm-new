package com.fieldservice.dispatch.web;

import com.fieldservice.workorder.web.AssignmentWarning;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response envelope for a successful work order assignment.
 *
 * <p>{@code partsWarning} is null when all required parts are fully stocked
 * or when the work order has no required parts. It is advisory — it never blocks
 * the assignment.
 */
public record AssignmentResponse(AssignmentData data, Meta meta) {

    public record AssignmentData(
            UUID             assignmentId,
            UUID             workOrderId,
            UUID             technicianId,
            String           state,
            Instant          assignedAt,
            Integer          recommendationRank,
            BigDecimal       recommendationScore,
            boolean          overrideRecorded,
            AssignmentWarning partsWarning
    ) {}

    public record Meta(String traceId) {}
}
