package com.fieldservice.workorder.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request DTO for work order creation (POST /api/v1/work-orders).
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateWorkOrderRequest(

        @NotNull
        UUID customerId,

        @NotNull
        UUID siteId,

        @NotBlank
        @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL",
                 message = "priority must be one of LOW, MEDIUM, HIGH, CRITICAL")
        String priority,

        @NotBlank
        @Size(max = 500)
        String title,

        String description
) {}
