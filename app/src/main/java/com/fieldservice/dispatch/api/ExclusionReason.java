package com.fieldservice.dispatch.api;

/**
 * Fixed reason codes for technician exclusion from a dispatch recommendation set.
 *
 * <p>Every exclusion carries exactly one primary reason — the first failing rule when
 * rules are evaluated in the declared enum order (INACTIVE → CERTIFICATION_MISSING →
 * CERTIFICATION_EXPIRED → UNAVAILABLE_IN_WINDOW → OUT_OF_REACH).
 */
public enum ExclusionReason {

    /** Technician's {@code is_active} flag is {@code false}. */
    INACTIVE_TECHNICIAN,

    /** Technician holds no certification record for at least one required type code. */
    CERTIFICATION_MISSING,

    /** Technician holds a record for the required type but every instance has expired. */
    CERTIFICATION_EXPIRED,

    /** No working window covers the service interval, or an approved absence overlaps it. */
    UNAVAILABLE_IN_WINDOW,

    /** Technician's home-base exceeds the configured Haversine reach radius. */
    OUT_OF_REACH
}
