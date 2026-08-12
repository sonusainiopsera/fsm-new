package com.fieldservice.privacy.api;

/**
 * Persisted lifecycle states for a DSAR request.
 *
 * <p>Terminal states: FULFILLED, REJECTED, WITHDRAWN.
 */
public enum DsarState {
    /** Intake recorded; identity verification not yet started. */
    RECEIVED,
    /** Verification workflow initiated but confirmation not yet recorded. */
    IDENTITY_PENDING,
    /** Identity confirmed; eligible for export assembly. */
    VERIFIED,
    /** Export job has claimed this request and is assembling the artifact. */
    IN_PROGRESS,
    /** Export artifact produced and available for download. */
    FULFILLED,
    /** Request rejected (e.g. fraudulent, no subject data found). */
    REJECTED,
    /** Subject withdrew the request before fulfilment. */
    WITHDRAWN
}
