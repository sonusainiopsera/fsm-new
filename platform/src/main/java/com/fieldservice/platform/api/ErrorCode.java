package com.fieldservice.platform.api;

/**
 * Stable machine-readable error codes for all API error responses.
 * These codes are part of the public API contract — once published they must
 * not be renamed or removed, only deprecated and superseded.
 * Clients MUST branch on {@code code}, never on {@code message} text.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    NOT_FOUND,
    FORBIDDEN,
    ILLEGAL_TRANSITION,
    GUARD_REFUSED,
    CONFLICT,
    RATE_LIMITED,
    PROVIDER_DEGRADED,
    INTERNAL_ERROR
}
