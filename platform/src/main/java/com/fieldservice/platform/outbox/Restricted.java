package com.fieldservice.platform.outbox;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a payload field as data-classification <em>Restricted</em>.
 *
 * <p>Fields carrying this annotation — password hashes, session tokens, provider
 * credentials — must <strong>never</strong> appear in an outbox payload. If a
 * {@code @Restricted} field is non-null at publish time, {@link PiiRedaction}
 * throws {@link RestrictedFieldException} and aborts the transaction.
 *
 * <p>Apply to fields of purpose-built payload records to document the exclusion
 * contract; the fail-fast check is exercised by unit tests for every new payload type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Restricted {}
