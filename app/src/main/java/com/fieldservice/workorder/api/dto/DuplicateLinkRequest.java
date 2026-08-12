package com.fieldservice.workorder.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request body for POST /api/v1/work-orders/{id}/duplicate-of. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DuplicateLinkRequest(

        @NotNull(message = "targetWorkOrderId is required")
        UUID targetWorkOrderId,

        @NotNull(message = "reason is required")
        @Size(max = 500, message = "reason must not exceed 500 characters")
        String reason
) {}
