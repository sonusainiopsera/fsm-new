package com.fieldservice.workorder.web;

import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight board projection for the work order collection endpoint.
 *
 * <p>Exposes only the fields needed by the dispatcher board, technician job list, and
 * customer portal. Full aggregate data (description, audit history) is not included.
 */
public record WorkOrderBoardRow(
        UUID id,
        String reference,
        WorkOrderStatus state,
        String priority,
        UUID siteId,
        String siteName,
        UUID assignedTechnicianId,
        Instant createdAt,
        Instant responseDeadline,
        Instant resolutionDeadline,
        boolean atRisk,
        int cumulativeHoldMinutes,
        Integer version) {

    public static WorkOrderBoardRow from(WorkOrder wo) {
        return new WorkOrderBoardRow(
                wo.getId(),
                wo.getReference(),
                wo.getState(),
                wo.getPriority(),
                wo.getSite().getId(),
                wo.getSite().getName(),
                wo.getAssignedTechnicianId(),
                wo.getCreatedAt(),
                wo.getResponseDeadline(),
                wo.getResolutionDeadline(),
                wo.isAtRisk(),
                wo.getCumulativeHoldMinutes(),
                wo.getVersion());
    }
}
