package com.fieldservice.workorder.web;

import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import com.fieldservice.workorder.lifecycle.WorkOrderHoldReasonCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/work-orders/{id}/transitions.
 *
 * <p>{@code holdReasonCode} is required when {@code event} is {@code HOLD}; for all other
 * events it is optional and ignored.
 */
public record TransitionRequest(
        @NotNull WorkOrderEvent event,
        @NotNull Integer expectedVersion,
        @Size(max = 500) String reason,
        WorkOrderHoldReasonCode holdReasonCode) {

    @AssertTrue(message = "holdReasonCode is required for HOLD events")
    public boolean isHoldReasonCodeValidForEvent() {
        return event == null || event != WorkOrderEvent.HOLD || holdReasonCode != null;
    }
}
