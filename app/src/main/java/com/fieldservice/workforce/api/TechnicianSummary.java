package com.fieldservice.workforce.api;

import java.util.List;
import java.util.UUID;

/**
 * Read-only projection of a technician profile for public consumers (e.g. dispatch).
 *
 * <p>Mobile phone is intentionally excluded — it is CONFIDENTIAL and only exposed
 * through the workforce write API to callers with ADMIN or MANAGER authority.
 */
public record TechnicianSummary(
        UUID id,
        UUID userId,
        String employeeCode,
        String displayName,
        String timezone,
        UUID homeBaseSiteId,
        boolean active,
        List<SkillRef> skills
) {}
