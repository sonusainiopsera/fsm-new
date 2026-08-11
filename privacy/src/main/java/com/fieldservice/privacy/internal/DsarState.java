package com.fieldservice.privacy.internal;

/**
 * Persisted lifecycle states for a {@code dsar_request}.
 *
 * <p>Terminal states: {@link #FULFILLED}, {@link #REJECTED}, {@link #WITHDRAWN}.
 * No events produce outbound transitions from these states.
 */
enum DsarState {
    /** Initial state — request received, awaiting acknowledgement. */
    RECEIVED,
    /** Request acknowledged; subject identity verification is outstanding. */
    IDENTITY_PENDING,
    /** Identity verified; ready for export assembly. */
    VERIFIED,
    /** Export assembly running (claimed by the worker job). */
    IN_PROGRESS,
    /** Export assembled and available for download. Terminal state. */
    FULFILLED,
    /** Request rejected (e.g. cannot verify identity, outside scope). Terminal state. */
    REJECTED,
    /** Subject withdrew the request. Terminal state. */
    WITHDRAWN
}
