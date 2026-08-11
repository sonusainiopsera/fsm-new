package com.fieldservice.platform.api.exception;

/**
 * Thrown when authentication fails for any reason (unknown email, wrong password,
 * inactive account, locked account, grantless user).
 *
 * <p>All authentication failure reasons collapse to this single exception so the
 * handler can return a body-identical 401 response regardless of the underlying
 * cause — accounts cannot be enumerated via different response shapes.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String internalReason) {
        super(internalReason);
    }
}
