package com.fieldservice.app.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Uniform API error envelope returned for all error responses.
 *
 * <p>All fields are stable across API versions. The {@code detail} field is intentionally
 * absent for 403 (scoped access denial) responses to preserve non-disclosure — no resource
 * identity or existence information leaks through the error body.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String detail,
        String traceId) {

    /**
     * Creates a 403 non-disclosure response. The {@code detail} field is {@code null} so
     * that out-of-scope and nonexistent resource probes produce byte-identical bodies.
     */
    public static ErrorResponse forbidden(String traceId) {
        return new ErrorResponse(
                Instant.now(),
                403,
                "Forbidden",
                "ACCESS_DENIED",
                null,
                traceId);
    }

    /** Creates a generic error response. */
    public static ErrorResponse of(int status, String error, String code, String detail, String traceId) {
        return new ErrorResponse(Instant.now(), status, error, code, detail, traceId);
    }
}
