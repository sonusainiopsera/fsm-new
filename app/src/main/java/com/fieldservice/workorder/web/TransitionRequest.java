package com.fieldservice.workorder.web;

import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for POST /api/v1/work-orders/{id}/transitions.
 *
 * <p>{@code holdReasonCode} is required when {@code event} is {@code HOLD} and must
 * match an active entry in the hold reason vocabulary (enforced by
 * {@code HoldReasonService.validate} before guards run).
 *
 * <p>{@code acknowledgeWarnings} and {@code warningAcknowledgementReason} are optional
 * for ASSIGN events; if the server returns advisory parts warnings, the dispatcher may
 * re-submit with these fields to record that the warning was reviewed and accepted.
 */
public record TransitionRequest(
        @NotNull WorkOrderEvent event,
        @NotNull Integer expectedVersion,
        @Size(max = 500) String reason,
        @Size(max = 50) String holdReasonCode,
        UUID technicianId,
        Boolean acknowledgeWarnings,
        @Size(max = 500) String warningAcknowledgementReason) {

    @AssertTrue(message = "holdReasonCode is required for HOLD events")
    public boolean isHoldReasonCodeValidForEvent() {
        return event == null || event != WorkOrderEvent.HOLD || holdReasonCode != null;
    }
}
