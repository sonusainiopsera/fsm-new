package com.fieldservice.platform.api;

/**
 * A single field-level validation error included in 400 responses.
 *
 * @param field   the request field path (e.g. "priority", "body.description")
 * @param message human-readable constraint violation message; no internals
 */
public record FieldError(String field, String message) {}
