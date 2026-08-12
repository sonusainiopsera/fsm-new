package com.fieldservice.dispatch.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for POST /api/v1/work-orders/{workOrderId}/assignment.
 *
 * <p>Unknown properties are rejected ({@code ignoreUnknown = false}) so that
 * clients cannot accidentally include unsupported fields that would silently vanish.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AssignmentRequest(

        @NotNull(message = "technicianId must not be null")
        UUID technicianId,

        UUID recommendationSnapshotId,

        @Size(max = 1000, message = "overrideReason must be 1000 characters or fewer")
        String overrideReason
) {}
