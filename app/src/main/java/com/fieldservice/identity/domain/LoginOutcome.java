package com.fieldservice.identity.domain;

/**
 * Possible outcomes of a login attempt, mirroring the {@code login_audit.outcome}
 * CHECK constraint vocabulary.
 */
public enum LoginOutcome {
    SUCCESS,
    WRONG_PASSWORD,
    UNKNOWN_EMAIL,
    INACTIVE,
    LOCKED,
    GRANTLESS,
    LOCKOUT_STORE_UNAVAILABLE
}
