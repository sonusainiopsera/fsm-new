package com.fieldservice.dispatch.api;

/**
 * Controlled vocabulary for reassignment reasons (WO-139).
 *
 * <p>Every reassignment must supply exactly one value from this enum. Free-text notes
 * may supplement but never replace the controlled code. The vocabulary is mirrored
 * as a CHECK constraint in the V67 migration so an unknown code is rejected by the
 * database even if the application layer is bypassed.
 */
public enum ReassignmentReason {

    /** The currently assigned technician is unavailable (sick, emergency, schedule conflict). */
    TECHNICIAN_UNAVAILABLE,

    /** The current job has overrun and the work order cannot wait for the same technician. */
    JOB_OVERRUN,

    /** The work order requires skills the currently assigned technician does not hold. */
    SKILL_MISMATCH,

    /** An at-risk alert indicates reassignment is needed to avoid an SLA breach. */
    SLA_RISK,

    /** The customer has explicitly requested a different technician. */
    CUSTOMER_REQUEST,

    /** Parts required for the job are unavailable at the current technician's location. */
    PARTS_UNAVAILABLE,

    /** A reason not covered by the other values; free-text notes are strongly encouraged. */
    OTHER
}
