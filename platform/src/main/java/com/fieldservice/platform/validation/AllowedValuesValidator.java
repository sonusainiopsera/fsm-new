package com.fieldservice.platform.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public class AllowedValuesValidator implements ConstraintValidator<AllowedValues, String> {

    private Set<String> allowed;

    @Override
    public void initialize(AllowedValues annotation) {
        allowed = Arrays.stream(annotation.values()).collect(Collectors.toSet());
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // null handled by @NotNull
        }
        return allowed.contains(value);
    }
}
