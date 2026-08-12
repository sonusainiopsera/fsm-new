package com.fieldservice.sla.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cross-field validation for SLA policy requests.
 *
 * <p>Enforces:
 * <ul>
 *   <li>resolutionMinutes &gt;= responseMinutes</li>
 *   <li>responseMinutes &gt; 0 (supplementary to {@code @Positive})</li>
 * </ul>
 *
 * <p>Each violation produces a named field error so the API caller receives
 * field-level structured errors rather than a generic constraint message.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SlaPolicyConstraintValidator.class)
@Documented
public @interface SlaPolicyValid {

    String message() default "resolutionMinutes must be >= responseMinutes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
