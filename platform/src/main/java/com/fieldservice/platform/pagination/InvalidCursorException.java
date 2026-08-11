package com.fieldservice.platform.pagination;

/**
 * Thrown when a keyset cursor is malformed, tampered with, or replayed against
 * a different sort order. Mapped to 400 by {@code GlobalExceptionHandler}.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String reason) {
        super("Invalid pagination cursor: " + reason);
    }
}
