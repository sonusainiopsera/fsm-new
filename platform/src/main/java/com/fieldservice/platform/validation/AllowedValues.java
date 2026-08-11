package com.fieldservice.platform.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a string or enum value is one of a fixed allow-list.
 *
 * <p>Use this instead of relying on Jackson's lenient enum coercion, which can silently
 * accept out-of-vocabulary values in some configurations. Pairing this constraint with
 * Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} and strict enum deserialization closes
 * the mass-assignment vector at the input boundary.
 *
 * <p>Usage:
 * <pre>
 * {@literal @}AllowedValues({"LOW", "MEDIUM", "HIGH", "CRITICAL"})
 * private String priority;
 * </pre>
 */
@Documented
@Constraint(validatedBy = AllowedValuesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedValues {

    /** The set of values that are accepted. */
    String[] value();

    /** Whether matching is case-sensitive. Default true. */
    boolean caseSensitive() default true;

    String message() default "Value is not in the allowed set.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
