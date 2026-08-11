package com.fieldservice.platform.api.exception;

/** Thrown on a duplicate-key or stale-version conflict. Maps to HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
