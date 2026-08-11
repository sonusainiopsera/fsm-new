package com.fieldservice.platform.api;

import java.util.List;

/**
 * Uniform API error envelope returned for every non-2xx response.
 *
 * <p>The four fields are stable public API contract:
 * <ul>
 *   <li>{@code code}        — stable {@link ErrorCode} string; clients branch on this</li>
 *   <li>{@code message}     — human-readable summary; no stack traces, SQL, or class names</li>
 *   <li>{@code fieldErrors} — per-field violations on 400; empty list otherwise</li>
 *   <li>{@code traceId}     — MDC trace-id for log correlation; never null</li>
 * </ul>
 *
 * <p>For 403 responses the body is deliberately identical regardless of whether the
 * resource exists (non-disclosure / BR-19 / OWASP A01).
 */
public record ApiErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String traceId) {

    static final String FORBIDDEN_MESSAGE = "Access denied.";
    static final List<FieldError> NO_FIELDS = List.of();

    public static ApiErrorResponse forbidden(String traceId) {
        return new ApiErrorResponse(ErrorCode.FORBIDDEN.name(), FORBIDDEN_MESSAGE, NO_FIELDS, traceId);
    }

    public static ApiErrorResponse of(ErrorCode code, String message, String traceId) {
        return new ApiErrorResponse(code.name(), message, NO_FIELDS, traceId);
    }

    public static ApiErrorResponse withFieldErrors(ErrorCode code, String message,
                                                    List<FieldError> errors, String traceId) {
        return new ApiErrorResponse(code.name(), message, errors, traceId);
    }
}
