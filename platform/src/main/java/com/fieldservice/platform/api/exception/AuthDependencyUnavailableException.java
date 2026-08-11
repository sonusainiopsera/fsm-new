package com.fieldservice.platform.api.exception;

/**
 * Thrown when a critical authentication dependency (e.g., the lockout store) is
 * unavailable. Login fails closed rather than permitting unlimited guessing.
 */
public class AuthDependencyUnavailableException extends RuntimeException {

    public AuthDependencyUnavailableException(String dependency, Throwable cause) {
        super("Authentication dependency unavailable: " + dependency, cause);
    }
}
