package com.fieldservice.platform.outbox.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as carrying <b>Restricted</b> data (password hashes, tokens, provider credentials).
 *
 * <p>Restricted fields must never appear in event payloads, log lines, or HTTP responses.
 * {@link com.fieldservice.platform.outbox.PiiRedactionUtility} will throw
 * {@link com.fieldservice.platform.outbox.RestrictedDataInPayloadException} if a payload record
 * contains a field annotated with this annotation — fail-fast rather than silently leaking.
 *
 * <p>This annotation is checked reflectively at runtime during payload construction, not at
 * compile time, so adding it to an existing field is a safe, non-breaking change.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Restricted {
}
