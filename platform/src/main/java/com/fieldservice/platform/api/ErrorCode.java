package com.fieldservice.platform.api;

/**
 * Stable machine-readable error codes included in every API error response.
 * Clients must branch on these codes, never on message text.
 * These values are part of the public API contract and must not be renamed.
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
    INTERNAL_ERROR,
    IDEMPOTENCY_CONFLICT,
    WORK_ORDER_ILLEGAL_TRANSITION,
    WORK_ORDER_GUARD_REFUSED,
    WORK_ORDER_VERSION_CONFLICT,
    AI_PROVIDER_UNAVAILABLE,
    AI_DAILY_LIMIT_REACHED,
    PAYLOAD_TOO_LARGE,
    INVALID_CREDENTIALS,
    AUTH_DEPENDENCY_UNAVAILABLE
}
