package com.fieldservice.platform.outbox;

/**
 * Thrown when a payload contains a non-null {@link Restricted}-annotated field.
 * Aborts the enclosing transaction — Restricted data must never reach the outbox.
 */
public class RestrictedFieldException extends RuntimeException {

    private final String fieldName;

    public RestrictedFieldException(String fieldName) {
        super("Payload field '" + fieldName + "' is @Restricted and must not be set on outbox payloads");
        this.fieldName = fieldName;
    }

    public String getFieldName() { return fieldName; }
}
