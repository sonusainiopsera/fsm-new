package com.fieldservice.dispatch.api;

/**
 * Machine-readable reason codes for technician exclusions from the eligibility gate.
 *
 * <p>Order reflects the priority used by EligibilityFilter when multiple reasons
 * apply simultaneously — the first matching reason is the primary exclusion reason.
 */
public enum ExclusionReason {

    /** Technician's active flag is false at the time of evaluation. */
    INACTIVE_TECHNICIAN,

    /** No certification record of the required type exists for this technician. */
    CERTIFICATION_MISSING,

    /**
     * A certification of the required type exists but every instance has an
     * expiry date strictly before the service window start date.
     * No grace period is applied — expired means absent.
     */
    CERTIFICATION_EXPIRED,

    /**
     * The technician has no shift window covering the requested service window,
     * or an approved absence overlaps the window.
     */
    UNAVAILABLE_IN_WINDOW,

    /**
     * The straight-line Haversine distance from the technician's home-base site
     * to the work order site exceeds the configured maximum reach radius.
     */
    OUT_OF_REACH,

    /**
     * The technician is already committed to a confirmed customer appointment window
     * that overlaps with the target work order's service window.
     * Applied only during reassignment candidate generation.
     */
    CONFIRMED_APPOINTMENT_CONFLICT,
}
