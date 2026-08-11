package com.fieldservice.platform.crypto;

/**
 * Thrown when the key-management service is unreachable or returns an error.
 *
 * <p>Callers must treat this as a fail-closed signal: writes must not persist
 * plaintext and reads must return a degraded marker rather than exposing
 * unencrypted data.
 */
public class KeyManagementUnavailableException extends RuntimeException {

    public KeyManagementUnavailableException(String message) {
        super(message);
    }

    public KeyManagementUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
