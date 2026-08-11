package com.fieldservice.platform.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a String field does not exceed the configured length cap and contains
 * only characters from the allowed set (printable ASCII, excluding HTML-injection vectors).
 * Null values are considered valid (use @NotBlank separately if required).
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SafeTextValidator.class)
public @interface SafeText {
    int maxLength() default 1000;
    String message() default "contains invalid characters or exceeds maximum length";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
