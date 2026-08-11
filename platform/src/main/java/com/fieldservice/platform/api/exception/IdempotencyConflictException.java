package com.fieldservice.platform.api.exception;

/**
 * Thrown when a mutating request supplies an Idempotency-Key that was already used
 * with a <em>different</em> request payload (hash mismatch).
 *
 * <p>Mapped to HTTP 409 by {@link com.fieldservice.platform.web.GlobalExceptionHandler}.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency-Key reuse: the same key was previously sent with a different request payload.");
    }
}
