package com.fieldservice.platform.crypto;

/**
 * Thrown when the key-management service is unavailable and a key operation cannot
 * complete.  Writers must propagate this as a 503-class response; readers return
 * {@link EnvelopeEncryptedStringConverter#DEGRADED_MARKER} rather than re-throwing.
 */
public class KeyManagementUnavailableException extends RuntimeException {

    public KeyManagementUnavailableException(String message) {
        super(message);
    }

    public KeyManagementUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
