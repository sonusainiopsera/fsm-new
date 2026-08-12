package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.DisposalMethod;
import com.fieldservice.privacy.api.RetentionPeriodUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for PUT /api/v1/privacy/retention-policies/{id}.
 */
public record RetentionPolicyRequest(

        @Min(value = 1, message = "periodValue must be at least 1")
        int periodValue,

        @NotNull(message = "periodUnit is required")
        RetentionPeriodUnit periodUnit,

        @NotNull(message = "disposalMethod is required")
        DisposalMethod disposalMethod,

        boolean legalHold,

        boolean ratified,

        boolean enabled,

        String notes,

        @NotNull(message = "version is required for optimistic locking")
        Integer version
) {}
