package com.fieldservice.dispatch.internal;

/**
 * Controlled vocabulary for reassignment reasons.
 *
 * <p>Persisted as TEXT with a CHECK constraint that mirrors these values.
 * A reason from this list is mandatory on every reassignment; free text
 * ({@code reasonNotes}) may supplement but never replace it.
 */
public enum ReassignmentReason {

    /** Assigned technician is unavailable (sick, absent, emergency). */
    TECHNICIAN_UNAVAILABLE,

    /** The prior job is running over its estimated duration. */
    JOB_OVERRUN,

    /** A skill or certification mismatch was identified after initial assignment. */
    SKILL_MISMATCH,

    /** SLA at-risk signal triggered a proactive intervention. */
    SLA_RISK,

    /** Customer explicitly requested a different technician. */
    CUSTOMER_REQUEST,

    /** Required parts are not available with the assigned technician. */
    PARTS_UNAVAILABLE,

    /** Reason not covered by the above categories (requires notes). */
    OTHER,
}
