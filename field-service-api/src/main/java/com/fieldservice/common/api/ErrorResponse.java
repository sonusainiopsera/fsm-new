package com.fieldservice.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Standard error envelope returned by all error responses.
 *
 * @param code        machine-readable error code (e.g. "VALIDATION_FAILED", "ACCESS_DENIED")
 * @param message     human-readable summary
 * @param fieldErrors per-field validation errors; present only on 400 responses
 * @param traceId     correlation id from the request (MDC or X-B3-TraceId)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String traceId
) {

    /**
     * Per-field validation error detail.
     *
     * @param field   the field path that failed (e.g. "preferences[0].channel")
     * @param message the validation message
     */
    public record FieldError(String field, String message) {}

    // ── Factory helpers ──────────────────────────────────────────────────────

    public static ErrorResponse of(String code, String message, String traceId) {
        return new ErrorResponse(code, message, null, traceId);
    }

    public static ErrorResponse withFieldErrors(String code, String message,
                                                List<FieldError> fieldErrors, String traceId) {
        return new ErrorResponse(code, message, fieldErrors, traceId);
    }
}
