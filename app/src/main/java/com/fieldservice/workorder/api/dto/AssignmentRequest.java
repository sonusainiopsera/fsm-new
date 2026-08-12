package com.fieldservice.workorder.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for POST /api/v1/work-orders/{id}/assignment.
 *
 * <p>{@code acknowledgeWarnings} and {@code warningAcknowledgementReason} are persisted
 * on the assignment audit record regardless of whether a warning was shown,
 * to satisfy BR-14 root-cause requirements.
 */
public record AssignmentRequest(

        @NotNull(message = "technicianId is required")
        UUID technicianId,

        @NotNull(message = "expectedVersion is required")
        Integer expectedVersion,

        boolean acknowledgeWarnings,

        @Size(max = 500, message = "warningAcknowledgementReason must not exceed 500 characters")
        String warningAcknowledgementReason
) {}
