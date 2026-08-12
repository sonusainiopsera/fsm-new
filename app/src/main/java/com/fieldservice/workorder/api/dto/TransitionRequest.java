package com.fieldservice.workorder.api.dto;

import com.fieldservice.workorder.lifecycle.WorkOrderEvent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for POST /api/v1/work-orders/{id}/transitions.
 *
 * <p>{@code event} is bound to the {@link WorkOrderEvent} enum by name; an unknown value
 * fails with 400 before any lookup, never a 500 from enum coercion.
 *
 * <p>No {@code state} or {@code status} field is present — state is always derived from
 * the event, enforcing the event-based lifecycle contract.
 *
 * <p>{@code shortfalls} is optional and only meaningful for HOLD events with
 * {@code holdReasonCode = "AWAITING_PARTS"}. Each entry identifies a part and the
 * quantity that could not be sourced from available stock.
 */
public record TransitionRequest(

        @NotNull(message = "event is required")
        WorkOrderEvent event,

        @NotNull(message = "expectedVersion is required")
        Integer expectedVersion,

        @Size(max = 500, message = "reason must not exceed 500 characters")
        String reason,

        String holdReasonCode,

        List<ShortfallEntry> shortfalls

) {
    /** A single part shortfall within an AWAITING_PARTS hold request. */
    public record ShortfallEntry(UUID partId, int quantity) {}
}
