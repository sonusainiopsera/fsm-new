package com.fieldservice.platform.api.exception;

/**
 * Thrown when a keyset cursor is malformed, has been tampered with, or was issued
 * against a different sort order than the one currently requested.
 *
 * <p>Maps to HTTP 400 with a {@code fieldErrors} entry naming the {@code cursor} parameter.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String reason) {
        super(reason);
    }

    public InvalidCursorException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
