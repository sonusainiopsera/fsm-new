package com.fieldservice.platform.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.Instant;
import java.util.List;

/**
 * Canonical error response contract.
 * All API error responses must use this structure.
 */
@JsonInclude(Include.NON_NULL)
public record ErrorResponse(
        String status,
        int code,
        String message,
        String traceId,
        Instant timestamp,
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {}

    public static ErrorResponse of(int code, String status, String message, String traceId) {
        return new ErrorResponse(status, code, message, traceId, Instant.now(), null);
    }

    public static ErrorResponse withFieldErrors(int code, String status, String message,
                                                 String traceId, List<FieldError> fieldErrors) {
        return new ErrorResponse(status, code, message, traceId, Instant.now(), fieldErrors);
    }
}
