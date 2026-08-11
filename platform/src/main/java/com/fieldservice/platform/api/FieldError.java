package com.fieldservice.platform.api;

/**
 * Represents a single field-level validation error.
 *
 * <p>Included in {@link ErrorEnvelope#fieldErrors()} when the error originated from
 * input validation (HTTP 400). Never included for security or server errors.
 *
 * @param field   the request field path (e.g. {@code "priority"}, {@code "description"})
 * @param message a human-readable description of why the field was rejected; never
 *                contains the invalid value to avoid echoing user input
 */
public record FieldError(String field, String message) {
}
