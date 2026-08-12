package com.fieldservice.workforce.internal;

/** Kind of readiness requirement — determines how completeness is evaluated. */
enum RequirementKind {
    /** The named technician profile field must be non-null and non-blank. */
    PROFILE_FIELD,
    /** The technician must hold an active, current certification of this type. */
    CERTIFICATION_TYPE
}
