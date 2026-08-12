package com.fieldservice.workforce.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ReadinessRequirementRequest(
        @NotNull
        @Pattern(regexp = "PROFILE_FIELD|CERTIFICATION_TYPE",
                 message = "must be PROFILE_FIELD or CERTIFICATION_TYPE")
        String requirementKind,

        String fieldName,
        String certificationTypeCode,
        String technicianCategory
) {}
