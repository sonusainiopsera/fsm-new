package com.fieldservice.privacy.internal;

/**
 * Events that drive DSAR lifecycle transitions.
 *
 * <p>Each value maps to exactly one or more arcs in {@link DsarTransitionTable}.
 */
enum DsarEvent {
    /** Acknowledge receipt; RECEIVED → IDENTITY_PENDING. */
    ACKNOWLEDGE,
    /** Record successful identity verification; IDENTITY_PENDING → VERIFIED. */
    VERIFY,
    /** Begin export assembly (manual or worker-initiated); VERIFIED → IN_PROGRESS. */
    START_PROCESSING,
    /** Mark export complete; IN_PROGRESS → FULFILLED. */
    FULFILL,
    /** Reject the request; RECEIVED|IDENTITY_PENDING|VERIFIED|IN_PROGRESS → REJECTED. */
    REJECT,
    /** Subject withdrew the request; RECEIVED|IDENTITY_PENDING|VERIFIED|IN_PROGRESS → WITHDRAWN. */
    WITHDRAW
}
