package com.fieldservice.workorder.web;

import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderHoldReasonCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for POST /api/v1/work-orders/{id}/transitions.
 *
 * <p>{@code holdReasonCode} is required when {@code event} is {@code HOLD}.
 * {@code technicianId} is required when {@code event} is {@code ASSIGN} and the
 * target work order has required competencies.
 */
public record TransitionRequest(
        @NotNull WorkOrderEvent event,
        @NotNull Integer expectedVersion,
        @Size(max = 500) String reason,
        WorkOrderHoldReasonCode holdReasonCode,
        UUID technicianId) {

    @AssertTrue(message = "holdReasonCode is required for HOLD events")
    public boolean isHoldReasonCodeValidForEvent() {
        return event == null || event != WorkOrderEvent.HOLD || holdReasonCode != null;
    }
}
