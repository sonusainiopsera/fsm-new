package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.DsarRequestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for POST /api/v1/privacy/dsar-requests.
 */
public record CreateDsarRequest(

        @NotNull(message = "requestType is required")
        DsarRequestType requestType,

        @NotBlank(message = "subjectType is required")
        String subjectType,

        @NotNull(message = "subjectId is required")
        UUID subjectId,

        String notes
) {}
