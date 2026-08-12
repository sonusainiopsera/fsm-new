package com.fieldservice.workforce.internal;

import java.util.UUID;

/**
 * Immutable read projection of a technician used by {@link CompletenessEvaluator}.
 * Contains only the fields that the evaluator inspects for profile completeness.
 */
record TechnicianReadModel(
        UUID   id,
        String employeeCode,
        String displayName,
        String mobilePhone,
        String timezone,
        UUID   homeBaseSiteId
) {}
