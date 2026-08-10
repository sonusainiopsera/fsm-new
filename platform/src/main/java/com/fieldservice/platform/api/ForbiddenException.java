package com.fieldservice.platform.api;

/**
 * Thrown when an authenticated caller lacks permission for an operation.
 * Maps to HTTP 403 with the same body as a not-found response for scoped
 * resources (non-disclosure contract).
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
