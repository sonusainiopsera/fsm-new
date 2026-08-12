package com.fieldservice.release;

/**
 * Outcome of a single gate evaluation.
 *
 * <p>SETUP_ERROR and TRANSPORT_ERROR are not gate failures — they indicate the
 * environment or network prevented evaluation. Only FAIL represents a confirmed
 * invariant violation that should block promotion.
 */
public enum GateStatus {
    /** Gate asserted successfully; invariant holds. */
    PASS,
    /** Invariant violated; promotion must be blocked. */
    FAIL,
    /** Gate was skipped (dry-run mode or prerequisite not met). */
    SKIP,
    /** Environment missing required account, part, or configuration. */
    SETUP_ERROR,
    /** Transport-level error after all retries exhausted. */
    TRANSPORT_ERROR
}
