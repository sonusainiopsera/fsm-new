package com.fieldservice.workorder.web;

import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only API response for a work order resource.
 */
public record WorkOrderResponse(
        UUID id,
        String reference,
        WorkOrderStatus state,
        String priority,
        UUID siteId,
        UUID assignedTechnicianId,
        Instant createdAt) {

    public static WorkOrderResponse from(WorkOrder wo) {
        return new WorkOrderResponse(
                wo.getId(),
                wo.getReference(),
                wo.getState(),
                wo.getPriority(),
                wo.getSite().getId(),
                wo.getAssignedTechnicianId(),
                wo.getCreatedAt());
    }
}
