package com.fieldservice.platform.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a string field contains only characters from the platform allow-list
 * and does not exceed the maximum length.
 *
 * <p>Allow-list: printable ASCII (0x20–0x7E) plus common Unicode letters, digits,
 * basic punctuation (spaces, hyphens, periods, commas, apostrophes, slashes), and
 * newlines in multi-line fields. Specifically rejects control characters, C0/C1 controls,
 * null bytes, and Unicode directional overrides.
 *
 * <p>Usage:
 * <pre>
 * {@literal @}SafeText(maxLength = 500)
 * private String description;
 * </pre>
 */
@Documented
@Constraint(validatedBy = SafeTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeText {

    /** Maximum character length (not bytes). Default 255. */
    int maxLength() default 255;

    /** Whether to allow newlines ({@code \n}, {@code \r}). Default false. */
    boolean allowNewlines() default false;

    String message() default "Field contains invalid characters or exceeds maximum length.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
