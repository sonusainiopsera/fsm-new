package com.fieldservice.sla.web.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates the cross-field invariant: {@code resolutionMinutes >= responseMinutes}.
 *
 * <p>Works for both {@link CreateSlaPolicyRequest} and {@link UpdateSlaPolicyRequest}
 * because both expose {@code responseMinutes()} and {@code resolutionMinutes()} accessors.
 */
public class SlaPolicyConstraintValidator implements ConstraintValidator<ValidSlaPolicy, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        Integer responseMinutes = null;
        Integer resolutionMinutes = null;

        if (value instanceof CreateSlaPolicyRequest r) {
            responseMinutes = r.responseMinutes();
            resolutionMinutes = r.resolutionMinutes();
        } else if (value instanceof UpdateSlaPolicyRequest r) {
            responseMinutes = r.responseMinutes();
            resolutionMinutes = r.resolutionMinutes();
        } else {
            return true;
        }

        if (responseMinutes == null || resolutionMinutes == null) {
            return true; // field-level @NotNull will report the null separately
        }
        if (resolutionMinutes >= responseMinutes) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        "resolutionMinutes must be >= responseMinutes")
                .addPropertyNode("resolutionMinutes")
                .addConstraintViolation();
        return false;
    }
}
