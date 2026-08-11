package com.fieldservice.platform.outbox.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as carrying <b>Confidential</b> data (contact details, GPS coordinates, addresses).
 *
 * <p>Confidential fields are masked with {@code "[REDACTED]"} in event payloads by
 * {@link com.fieldservice.platform.outbox.PiiRedactionUtility}. The field key is included in the
 * payload so consumers can see that data was present but suppressed; only the value is replaced.
 *
 * <p>Examples of Confidential data: customer phone numbers, email addresses, GPS coordinates,
 * site street addresses. These must not appear in broker messages, dead-letter queues, or logs.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Confidential {
}
