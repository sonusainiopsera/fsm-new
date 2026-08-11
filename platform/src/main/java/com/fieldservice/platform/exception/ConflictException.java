package com.fieldservice.platform.exception;

/**
 * Thrown when an operation cannot complete because of a conflicting resource state,
 * beyond illegal lifecycle transitions (use {@link IllegalTransitionException} for those).
 *
 * <p>Maps to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
