package com.fieldservice.platform.api.exception;

/**
 * Thrown when a domain-level business guard refuses the operation
 * (e.g. technician lacks a required certification). Maps to HTTP 422.
 */
public class BusinessGuardException extends RuntimeException {
    public BusinessGuardException(String message) { super(message); }
}
