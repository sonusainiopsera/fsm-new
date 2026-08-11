package com.fieldservice.platform.api;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error response envelope used for all API error responses.
 *
 * <p>The {@code code} field carries a stable, machine-readable error code (e.g.,
 * {@code "FORBIDDEN"}) that clients may use to drive behaviour. The {@code message}
 * field contains a human-readable description that never echoes client-supplied input,
 * resource identifiers, or any information that could facilitate a probing attack.
 *
 * <p>Non-disclosure contract: for 403 responses on scoped entities, the message is
 * deliberately generic ({@code "Access denied."}) regardless of whether the resource exists
 * or is merely out of scope, so a cross-role probe cannot distinguish the two cases.
 *
 * @param code        stable error code
 * @param message     human-readable message, never containing resource identifiers or PII
 * @param fieldErrors per-field validation errors; empty list when not applicable
 * @param traceId     request trace identifier for log correlation (safe to expose)
 * @param timestamp   UTC timestamp of the error
 */
public record ErrorEnvelope(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String traceId,
        Instant timestamp
) {

    /** Convenience constructor with no field errors. */
    public ErrorEnvelope(String code, String message, String traceId, Instant timestamp) {
        this(code, message, List.of(), traceId, timestamp);
    }

    /** Stable error codes used across the platform. */
    public static final class Code {
        private Code() {}

        /** 400 — request validation failed; see {@code fieldErrors} for per-field detail. */
        public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

        /** 401 — authentication required or credentials invalid. */
        public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

        /** 403 — access denied; also used for not-found on scoped entities (non-disclosure). */
        public static final String FORBIDDEN = "FORBIDDEN";

        /** 404 — resource not found (non-scoped entities only). */
        public static final String NOT_FOUND = "NOT_FOUND";

        /** 409 — illegal state transition or optimistic-lock conflict. */
        public static final String ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION";

        /** 409 — resource conflict (duplicate key, concurrent modification). */
        public static final String CONFLICT = "CONFLICT";

        /** 422 — business rule guard refused the operation. */
        public static final String GUARD_REFUSED = "GUARD_REFUSED";

        /** 429 — caller has exceeded the rate limit. */
        public static final String RATE_LIMITED = "RATE_LIMITED";

        /** 503 — upstream provider is unavailable. */
        public static final String PROVIDER_DEGRADED = "PROVIDER_DEGRADED";

        /** 500 — unexpected server error. */
        public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

        /** 409 — Idempotency-Key reused with a different request payload. */
        public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

        /** 409 — a concurrent request with the same Idempotency-Key is still in progress. */
        public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";

        /** 409 — the prior response for this key was too large to store for replay. */
        public static final String IDEMPOTENCY_NON_REPLAYABLE = "IDEMPOTENCY_NON_REPLAYABLE";
    }
}
