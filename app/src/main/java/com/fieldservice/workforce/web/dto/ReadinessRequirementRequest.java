package com.fieldservice.workforce.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for creating or updating a readiness requirement (ADMIN-only, AC-1).
 *
 * <p>Exactly one of {@code fieldName} or {@code certificationTypeCode} must be provided,
 * consistent with the DB constraint.
 */
public record ReadinessRequirementRequest(
        @NotNull String requirementKind,
        String fieldName,
        String certificationTypeCode,
        String technicianCategory,
        boolean active
) {}
