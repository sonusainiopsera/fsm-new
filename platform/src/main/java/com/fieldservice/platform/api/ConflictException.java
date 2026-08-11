package com.fieldservice.platform.api;

/**
 * Thrown when an operation conflicts with existing state (duplicate, concurrent modification).
 * Maps to HTTP 409 with code {@link ErrorCode#CONFLICT}.
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
