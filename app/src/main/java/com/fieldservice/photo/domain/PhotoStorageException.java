package com.fieldservice.photo.domain;

/**
 * Thrown when the object storage provider is unavailable or returns an unexpected error.
 *
 * <p>The service layer wraps this in
 * {@link com.fieldservice.platform.exception.ProviderDegradedException} so the global
 * exception handler maps it to HTTP 503.
 */
public class PhotoStorageException extends RuntimeException {

    public PhotoStorageException(String message) {
        super(message);
    }

    public PhotoStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
