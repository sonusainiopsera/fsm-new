package com.fieldservice.platform.security;

/**
 * Thrown when a caller attempts to access a resource that either does not exist
 * or is outside their row scope. The two cases are intentionally indistinguishable
 * to prevent existence-disclosure attacks (BR-19, OWASP A01).
 *
 * <p>This exception is mapped by {@link com.fieldservice.platform.web.GlobalExceptionHandler}
 * to a uniform 403 response with no resource detail.
 *
 * <p><strong>Non-disclosure policy:</strong> the message stored here is used only for
 * structured logging (never echoed to the client). The client always receives the
 * same opaque 403 envelope regardless of whether the record is absent or out-of-scope.
 */
public class ScopedAccessDeniedException extends RuntimeException {

    public ScopedAccessDeniedException(String message) {
        super(message);
    }

    public ScopedAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
