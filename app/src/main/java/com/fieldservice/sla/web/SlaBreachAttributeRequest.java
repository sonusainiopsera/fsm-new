package com.fieldservice.sla.web;

import com.fieldservice.sla.SlaBreachReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/sla/breaches/{id}/reason.
 *
 * <p>The {@code reasonCode} must be from the controlled vocabulary;
 * unknown values are rejected with HTTP 400 by Bean Validation before reaching the service.
 */
public record SlaBreachAttributeRequest(

        @NotNull(message = "reasonCode is required")
        SlaBreachReasonCode reasonCode,

        @Size(max = 500, message = "reasonNote must not exceed 500 characters")
        String reasonNote
) {}
