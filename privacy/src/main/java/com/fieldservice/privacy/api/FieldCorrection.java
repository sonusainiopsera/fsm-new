package com.fieldservice.privacy.api;

/**
 * A single field-level correction in a rectification request.
 *
 * <p>{@code entityName} is the simple JPA entity class name (e.g. "AppUser").
 * {@code fieldName} is the field name as declared in the classification registry.
 * {@code newValue} is the corrected plaintext value to store; it will be
 * envelope-encrypted by the owning module's rectifier before persistence.
 */
public record FieldCorrection(String entityName, String fieldName, String newValue) {}
