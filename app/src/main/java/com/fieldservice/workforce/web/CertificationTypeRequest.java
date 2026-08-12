package com.fieldservice.workforce.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating or updating a certification type.
 */
public record CertificationTypeRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,50}",
                message = "code must be 2-50 uppercase alphanumeric characters or underscores")
        String code,

        @NotBlank @Size(max = 255)
        String displayName,

        @NotNull
        Boolean regulated,

        @Positive
        Integer defaultValidityMonths
) {}
