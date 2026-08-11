package com.fieldservice.platform.pagination;

/**
 * Thrown when a keyset cursor is malformed, tampered, or replayed against a different sort order.
 *
 * <p>Mapped to {@code 400 Bad Request} by the global exception handler so callers receive
 * a structured error rather than a 500. The cause is never echoed to the client.
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String reason) {
        super(reason);
    }

    public InvalidCursorException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
