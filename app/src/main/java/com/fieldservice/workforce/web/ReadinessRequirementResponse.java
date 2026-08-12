package com.fieldservice.workforce.web;

import java.util.UUID;

public record ReadinessRequirementResponse(
        UUID    id,
        String  requirementKind,
        String  fieldName,
        String  certificationTypeCode,
        String  technicianCategory,
        boolean active
) {}
