package com.fieldservice.sla.web.dto;

import com.fieldservice.sla.SlaBreachReasonCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for the breach reason attribution endpoint.
 */
public record AttributeReasonRequest(

        @NotNull(message = "reasonCode is required")
        SlaBreachReasonCode reasonCode,

        @Size(max = 500, message = "note must not exceed 500 characters")
        String note
) {}
