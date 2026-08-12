package com.fieldservice.dispatch.internal;

/**
 * Thrown when assignment request fields fail domain validation (e.g. missing override reason,
 * snapshot not found, snapshot work order mismatch). Maps to HTTP 400.
 */
public class AssignmentValidationException extends RuntimeException {

    private final String field;
    private final String code;

    public AssignmentValidationException(String field, String code, String message) {
        super(message);
        this.field = field;
        this.code  = code;
    }

    public String getField() { return field; }
    public String getCode()  { return code; }
}
