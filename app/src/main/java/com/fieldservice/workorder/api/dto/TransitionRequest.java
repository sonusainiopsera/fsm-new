package com.fieldservice.workorder.api.dto;

import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/work-orders/{id}/transitions.
 *
 * <p>{@code event} is bound to the {@link WorkOrderEvent} enum by name; an unknown value
 * fails with 400 before any lookup, never a 500 from enum coercion.
 *
 * <p>No {@code state} or {@code status} field is present — state is always derived from
 * the event, enforcing the event-based lifecycle contract.
 */
public record TransitionRequest(

        @NotNull(message = "event is required")
        WorkOrderEvent event,

        @NotNull(message = "expectedVersion is required")
        Integer expectedVersion,

        @Size(max = 500, message = "reason must not exceed 500 characters")
        String reason,

        String holdReasonCode
) {}
