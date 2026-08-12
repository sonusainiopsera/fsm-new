package com.fieldservice.workorder.duplicates;

/**
 * Thrown when a duplicate link request cannot be fulfilled due to validation rules.
 * Maps to HTTP 422 via WorkOrderExceptionHandler.
 */
public class DuplicateLinkException extends RuntimeException {

    private final String code;

    public DuplicateLinkException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
