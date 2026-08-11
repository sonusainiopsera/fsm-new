package com.fieldservice.workorder.web;

import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.holds.WorkOrderHold;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only API response for a work order resource.
 *
 * <p>{@code holdReasonCode} and {@code holdStartedAt} are populated only on the
 * single-item detail endpoint when the work order currently has an open hold.
 * They are null (and omitted from JSON) in list responses.
 */
public record WorkOrderResponse(
        UUID id,
        String reference,
        WorkOrderStatus state,
        String priority,
        UUID siteId,
        UUID assignedTechnicianId,
        Instant createdAt,
        int cumulativeHoldMinutes,
        String holdReasonCode,
        Instant holdStartedAt,
        Instant responseDueAt,
        Instant resolutionDueAt,
        Instant atRiskAt,
        UUID appliedSlaPolicyId,
        Integer version) {

    /** For list responses — hold detail fields are absent. */
    public static WorkOrderResponse from(WorkOrder wo) {
        return new WorkOrderResponse(
                wo.getId(),
                wo.getReference(),
                wo.getState(),
                wo.getPriority(),
                wo.getSite().getId(),
                wo.getAssignedTechnicianId(),
                wo.getCreatedAt(),
                wo.getCumulativeHoldMinutes(),
                null,
                null,
                wo.getResponseDeadline(),
                wo.getResolutionDeadline(),
                wo.getAtRiskAt(),
                wo.getAppliedSlaPolicyId(),
                wo.getVersion());
    }

    /** For the single-item detail endpoint — includes the currently-open hold if any. */
    public static WorkOrderResponse fromDetail(WorkOrder wo, WorkOrderHold openHold) {
        return new WorkOrderResponse(
                wo.getId(),
                wo.getReference(),
                wo.getState(),
                wo.getPriority(),
                wo.getSite().getId(),
                wo.getAssignedTechnicianId(),
                wo.getCreatedAt(),
                wo.getCumulativeHoldMinutes(),
                openHold != null ? openHold.getReasonCode() : null,
                openHold != null ? openHold.getStartedAt()  : null,
                wo.getResponseDeadline(),
                wo.getResolutionDeadline(),
                wo.getAtRiskAt(),
                wo.getAppliedSlaPolicyId(),
                wo.getVersion());
    }
}
