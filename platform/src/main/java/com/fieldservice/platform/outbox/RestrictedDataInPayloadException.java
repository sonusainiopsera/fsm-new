package com.fieldservice.platform.outbox;

/**
 * Thrown when a payload record contains a field annotated
 * {@link com.fieldservice.platform.outbox.annotation.Restricted}.
 *
 * <p>Restricted fields (password hashes, tokens, provider credentials) must never appear in
 * event payloads. This exception aborts the enclosing transaction rather than letting the
 * domain change commit without its event, or persisting a payload that contains Restricted data.
 */
public class RestrictedDataInPayloadException extends RuntimeException {

    public RestrictedDataInPayloadException(String fieldName, Class<?> payloadType) {
        super("Field '" + fieldName + "' on " + payloadType.getSimpleName()
                + " is classified Restricted and must never appear in an event payload.");
    }
}
