package com.fieldservice.platform.api.exception;

/** Thrown when the caller is authenticated but lacks permission for the operation. Maps to HTTP 403. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
