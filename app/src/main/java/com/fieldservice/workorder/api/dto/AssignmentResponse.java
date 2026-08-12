package com.fieldservice.workorder.api.dto;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Response body for POST /api/v1/work-orders/{id}/assignment.
 *
 * <p>Extends the transition fields with an advisory {@code warnings} list.
 * Warnings are never empty when {@code partsWarningCode} was recorded;
 * they are always empty when parts data was unavailable (degraded path).
 */
public record AssignmentResponse(
        UUID workOrderId,
        WorkOrderState fromState,
        WorkOrderState toState,
        Integer version,
        Set<WorkOrderEvent> legalNextEvents,
        Instant occurredAt,
        List<AssignmentWarning> warnings
) {
    public AssignmentResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
