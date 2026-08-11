package com.fieldservice.platform.api;

import java.util.List;

/**
 * Uniform error response envelope returned for every non-2xx HTTP response.
 *
 * <ul>
 *   <li>{@code code}        – stable {@link ErrorCode} name; clients branch on this, not {@code message}</li>
 *   <li>{@code message}     – human-readable summary; never contains stack traces or internal detail</li>
 *   <li>{@code fieldErrors} – per-field violations for 400 validation failures; empty list otherwise</li>
 *   <li>{@code traceId}     – correlates this response to logs; never null</li>
 * </ul>
 */
public record ErrorResponse(
        String code,
        String message,
        List<FieldError> fieldErrors,
        String traceId
) {

    public static ErrorResponse of(ErrorCode code, String message, String traceId) {
        return new ErrorResponse(code.name(), message, List.of(), traceId);
    }

    public static ErrorResponse ofFields(String traceId, List<FieldError> fieldErrors) {
        return new ErrorResponse(
                ErrorCode.VALIDATION_FAILED.name(),
                "Request validation failed",
                fieldErrors,
                traceId);
    }
}
