package com.fieldservice.platform.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates {@link AllowedValues} constraints.
 *
 * <p>{@code null} values pass (use {@code @NotNull} separately if required).
 */
public class AllowedValuesValidator implements ConstraintValidator<AllowedValues, String> {

    private Set<String> allowed;
    private boolean caseSensitive;

    @Override
    public void initialize(AllowedValues annotation) {
        this.caseSensitive = annotation.caseSensitive();
        if (caseSensitive) {
            this.allowed = Arrays.stream(annotation.value()).collect(Collectors.toSet());
        } else {
            this.allowed = Arrays.stream(annotation.value())
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());
        }
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null) {
            return true;
        }
        String toCheck = caseSensitive ? value : value.toUpperCase();
        return allowed.contains(toCheck);
    }
}
