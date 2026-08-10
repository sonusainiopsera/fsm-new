package com.fieldservice.platform.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * Constrains a String field to one of a fixed allow-list of values.
 * Applied to enum-like string fields in request DTOs to prevent
 * out-of-vocabulary inputs from reaching service logic.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = AllowedValuesValidator.class)
public @interface AllowedValues {

    String[] values();

    String message() default "Value '${validatedValue}' is not allowed. Permitted values: {values}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
