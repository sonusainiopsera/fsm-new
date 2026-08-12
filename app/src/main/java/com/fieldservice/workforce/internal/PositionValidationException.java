package com.fieldservice.workforce.internal;

import com.fieldservice.platform.api.FieldError;

import java.util.List;

public class PositionValidationException extends RuntimeException {

    private final List<FieldError> fieldErrors;

    public PositionValidationException(List<FieldError> fieldErrors) {
        super("Position report validation failed");
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldError> getFieldErrors() { return fieldErrors; }
}
