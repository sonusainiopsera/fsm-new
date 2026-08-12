package com.fieldservice.photo.application;

/**
 * Thrown when photo registration is refused due to a missing, expired or mismatched
 * upload intent, or when the uploaded object cannot be verified.
 *
 * <p>Maps to HTTP 422 via the global exception handler.
 */
public class PhotoRegistrationException extends RuntimeException {

    private final String code;

    public PhotoRegistrationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
