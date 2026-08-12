package com.fieldservice.sla.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates cross-field SLA constraints without requiring a Spring context.
 *
 * <p>Validates any object that exposes {@code getResponseMinutes()} and
 * {@code getResolutionMinutes()} — both the create and update request records.
 *
 * <p>Rules:
 * <ol>
 *   <li>{@code resolutionMinutes} must be &gt;= {@code responseMinutes}</li>
 * </ol>
 *
 * <p>All per-field positivity constraints ({@code @Positive}, {@code @DecimalMin},
 * etc.) are declared on the request record fields themselves; this validator adds
 * only the cross-field rule to keep single-field and cross-field errors separate.
 */
public class SlaPolicyConstraintValidator
        implements ConstraintValidator<SlaPolicyValid, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext ctx) {
        if (value == null) {
            return true;
        }

        Integer responseMinutes   = extractInt(value, "getResponseMinutes");
        Integer resolutionMinutes = extractInt(value, "getResolutionMinutes");

        if (responseMinutes == null || resolutionMinutes == null) {
            return true;
        }

        if (resolutionMinutes < responseMinutes) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(
                            "resolutionMinutes must be >= responseMinutes ("
                            + responseMinutes + "), but was " + resolutionMinutes)
                    .addPropertyNode("resolutionMinutes")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }

    private Integer extractInt(Object obj, String methodName) {
        try {
            Object result = obj.getClass().getMethod(methodName).invoke(obj);
            return result instanceof Integer i ? i : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
