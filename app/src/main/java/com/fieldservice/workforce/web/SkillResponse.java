package com.fieldservice.workforce.web;

import java.util.UUID;

public record SkillResponse(
        UUID    id,
        String  code,
        String  displayName,
        boolean active
) {}
