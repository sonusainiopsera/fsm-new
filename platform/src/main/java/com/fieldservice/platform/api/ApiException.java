package com.fieldservice.platform.api;

/**
 * Base class for all typed API exceptions thrown by domain code.
 * Domain code throws these without knowing about HTTP — the GlobalExceptionHandler
 * maps them to the correct status codes.
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    protected ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected ApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
