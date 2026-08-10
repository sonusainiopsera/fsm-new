package com.fieldservice.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error response envelope used across all error responses.
 * Fields are stable across API versions so clients can reliably key on {@code code}.
 *
 * <p>The {@code detail} field is omitted for 403/401 responses to prevent
 * information disclosure.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorEnvelope(
        String code,
        String message,
        String traceId,
        Object detail
) {

    public ErrorEnvelope(String code, String message, String traceId) {
        this(code, message, traceId, null);
    }
}
