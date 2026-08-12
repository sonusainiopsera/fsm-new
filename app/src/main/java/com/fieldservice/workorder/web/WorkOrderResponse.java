package com.fieldservice.workorder.web;

import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.duplicates.DuplicateCandidate;
import com.fieldservice.workorder.holds.WorkOrderHold;

import java.time.Instant;
import java.util.List;
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
        String origin,
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
        Integer version,
        List<DuplicateCandidate> duplicateCandidates) {

    /** For list responses — hold detail and duplicate candidates are absent. */
    public static WorkOrderResponse from(WorkOrder wo) {
        return new WorkOrderResponse(
                wo.getId(),
                wo.getReference(),
                wo.getState(),
                wo.getPriority(),
                wo.getOrigin(),
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
                wo.getVersion(),
                null);
    }

    /** For creation responses — includes advisory duplicate candidates. */
    public static WorkOrderResponse from(WorkOrder wo, List<DuplicateCandidate> duplicateCandidates) {
        return new WorkOrderResponse(
                wo.getId(),
                wo.getReference(),
                wo.getState(),
                wo.getPriority(),
                wo.getOrigin(),
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
                wo.getVersion(),
                duplicateCandidates != null && !duplicateCandidates.isEmpty() ? duplicateCandidates : null);
    }

    /** For the single-item detail endpoint — includes the currently-open hold if any. */
    public static WorkOrderResponse fromDetail(WorkOrder wo, WorkOrderHold openHold) {
        return new WorkOrderResponse(
                wo.getId(),
                wo.getReference(),
                wo.getState(),
                wo.getPriority(),
                wo.getOrigin(),
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
                wo.getVersion(),
                null);
    }
}
