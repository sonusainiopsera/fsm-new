package com.fieldservice.platform.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * Constrains a String field to a maximum length and a character allow-list.
 * The default allow-list permits printable ASCII (0x20–0x7E) plus common
 * Unicode letters and numbers, blocking control characters, SQL meta-characters,
 * and HTML special characters that could enable injection attacks.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = SafeTextValidator.class)
public @interface SafeText {

    int max() default 1_000;

    /**
     * Regex pattern that every character in the value must match.
     * Defaults to printable ASCII plus common extended Latin characters.
     * Override to restrict further (e.g. alphanumeric-only) or relax
     * for fields that accept Unicode content.
     */
    String pattern() default "^[\\p{L}\\p{N}\\p{P}\\p{Z}\\p{M}&&[^<>&\"'\\\\]]*$";

    String message() default "Field contains invalid characters or exceeds maximum length";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
