package com.fieldservice.workforce.web.dto;

import java.util.UUID;

/** Response DTO for a readiness requirement record. */
public record ReadinessRequirementResponse(
        UUID id,
        String requirementKind,
        String fieldName,
        String certificationTypeCode,
        String technicianCategory,
        boolean active,
        int version
) {}
