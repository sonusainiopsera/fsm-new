package com.fieldservice.workforce.web;

import java.util.UUID;

public record TechnicianResponse(
        UUID    id,
        UUID    userId,
        String  employeeCode,
        String  displayName,
        String  timezone,
        UUID    homeBaseSiteId,
        boolean active,
        Integer version
) {}
