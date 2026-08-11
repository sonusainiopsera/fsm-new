package com.fieldservice.sla.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cross-field Bean Validation constraint for SLA policy requests.
 *
 * <p>Enforces: {@code resolutionMinutes >= responseMinutes}.
 * Field-level constraints (positive integers, at-risk fraction bounds) are declared
 * on the individual fields with standard annotations.
 */
@Documented
@Constraint(validatedBy = SlaPolicyConstraintValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSlaPolicy {

    String message() default "resolutionMinutes must be >= responseMinutes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
