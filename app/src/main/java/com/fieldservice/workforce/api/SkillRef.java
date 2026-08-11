package com.fieldservice.workforce.api;

import java.util.UUID;

/** Read-only reference to a skill, used in technician summaries and directory lookups. */
public record SkillRef(
        UUID id,
        String code,
        String displayName,
        boolean active
) {}
