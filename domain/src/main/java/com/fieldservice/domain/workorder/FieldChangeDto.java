package com.fieldservice.domain.workorder;

/**
 * Before/after value for a single field in one revision.
 */
public record FieldChangeDto(String field, Object before, Object after) {}
