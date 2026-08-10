package com.fieldservice.platform.api;

/**
 * A single field-level validation failure within an {@link ErrorResponse}.
 *
 * @param field   JSON property path of the offending field
 * @param message Human-readable description of the violation (no internals)
 */
public record FieldError(String field, String message) {}
