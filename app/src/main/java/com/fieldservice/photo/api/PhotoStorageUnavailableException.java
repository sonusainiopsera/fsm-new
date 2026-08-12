package com.fieldservice.photo.api;

/**
 * Thrown when the object storage provider is unreachable or returns an unexpected error.
 *
 * <p>Callers translate this to a 503 response with a retry affordance.
 * The message MUST NOT include the presigned URL or provider-specific detail.
 */
public class PhotoStorageUnavailableException extends RuntimeException {

    public PhotoStorageUnavailableException(String message) {
        super(message);
    }

    public PhotoStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
