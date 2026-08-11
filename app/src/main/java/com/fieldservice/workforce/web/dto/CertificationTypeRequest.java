package com.fieldservice.workforce.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request body for creating or updating a certification type. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CertificationTypeRequest(

        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,49}", message = "code must be UPPER_SNAKE_CASE")
        String code,

        @NotBlank
        @Size(max = 255)
        String displayName,

        @NotNull
        boolean regulated,

        @Positive
        Integer defaultValidityMonths,

        boolean active
) {}
