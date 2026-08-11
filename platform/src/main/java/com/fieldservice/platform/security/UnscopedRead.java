package com.fieldservice.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotates a method or type that intentionally reads scoped entities without applying
 * the row-scope predicate.
 *
 * <p><strong>Usage policy:</strong> This annotation is permitted only on the analytics
 * read model. Any new usage must be added to the allow-list file at
 * {@code platform/src/main/resources/unscoped-read-allowlist.txt} and reviewed.
 * A reflective test ({@code UnscopedReadEnumerationTest}) enumerates all usages at build
 * time and fails if any unlisted usage is found, ensuring the exception surface cannot
 * grow silently.
 *
 * <p>The {@link #justification()} attribute is mandatory and must reference a business rule,
 * design document, or ticket that authorises the opt-out.
 *
 * @see com.fieldservice.security.UnscopedReadEnumerationTest
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Documented
public @interface UnscopedRead {

    /**
     * Mandatory justification for why this read is not row-scoped.
     * Must reference a business rule, epic, or design document.
     *
     * <p>Example: {@code "Analytics read model — all data is pre-aggregated with no PII; see BR-22"}
     */
    String justification();

    /**
     * The scoped entity types this opt-out applies to, for documentation purposes.
     * Not enforced at runtime; used as self-documentation.
     */
    Class<?>[] entities() default {};
}
