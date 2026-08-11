package com.fieldservice.platform.api;

import java.time.Instant;

/**
 * Uniform error response envelope used for all API error responses.
 *
 * <p>The {@code code} field carries a stable, machine-readable error code (e.g.,
 * {@code "ACCESS_DENIED"}) that clients may use to drive behaviour. The {@code message}
 * field contains a human-readable description that never echoes client-supplied input,
 * resource identifiers, or any information that could facilitate a probing attack.
 *
 * <p>Non-disclosure contract: for 403 responses on scoped entities, the message is
 * deliberately generic ({@code "Access denied."}) regardless of whether the resource exists
 * or is merely out of scope, so a cross-role probe cannot distinguish the two cases.
 *
 * @param code      stable error code
 * @param message   human-readable message, never containing resource identifiers or PII
 * @param traceId   request trace identifier for log correlation (safe to expose)
 * @param timestamp UTC timestamp of the error
 */
public record ErrorEnvelope(
        String code,
        String message,
        String traceId,
        Instant timestamp
) {

    /** Stable error codes used across the platform. */
    public static final class Code {
        private Code() {}

        /** 403 — access denied; also used for not-found on scoped entities (non-disclosure). */
        public static final String ACCESS_DENIED = "ACCESS_DENIED";

        /** 401 — authentication required or credentials invalid. */
        public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

        /** 400 — request validation failed. */
        public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

        /** 404 — resource not found (non-scoped entities only). */
        public static final String NOT_FOUND = "NOT_FOUND";

        /** 409 — illegal state transition. */
        public static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";

        /** 422 — business rule guard failed. */
        public static final String GUARD_FAILED = "GUARD_FAILED";

        /** 500 — unexpected server error. */
        public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    }
}
