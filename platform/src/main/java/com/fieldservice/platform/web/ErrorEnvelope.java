package com.fieldservice.platform.web;

/**
 * @deprecated Use {@link com.fieldservice.platform.api.ErrorResponse} instead.
 */
@Deprecated(since = "WO-006", forRemoval = true)
public record ErrorEnvelope(String code, String message, String traceId, Object detail) {

    public ErrorEnvelope(String code, String message, String traceId) {
        this(code, message, traceId, null);
    }
}
