package com.fieldservice.platform.outbox;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a payload field as data-classification <em>Confidential</em>.
 *
 * <p>Contact details (email, phone) and GPS coordinates carrying this annotation
 * are masked to {@code "***"} (strings) or {@code null} (numeric/object) by
 * {@link PiiRedaction} before the payload is serialised to the outbox.
 *
 * <p>Apply to fields of purpose-built payload records, never to JPA entity fields.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Confidential {}
