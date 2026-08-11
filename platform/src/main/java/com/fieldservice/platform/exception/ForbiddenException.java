package com.fieldservice.platform.exception;

/**
 * Thrown when a principal attempts an operation they are not permitted to perform,
 * outside the row-scope enforcement path.
 *
 * <p>For row-scoped entity access denials, use
 * {@link com.fieldservice.platform.security.ScopedAccessDeniedException} instead.
 *
 * <p>Maps to HTTP 403 with a generic body (no existence disclosure).
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super("Access denied.");
    }

    public ForbiddenException(String message) {
        super(message);
    }
}
