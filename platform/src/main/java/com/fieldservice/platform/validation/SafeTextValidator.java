package com.fieldservice.platform.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class SafeTextValidator implements ConstraintValidator<SafeText, String> {

    private int max;
    private Pattern pattern;

    @Override
    public void initialize(SafeText annotation) {
        this.max = annotation.max();
        this.pattern = Pattern.compile(annotation.pattern(), Pattern.DOTALL);
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // null handled by @NotNull / @NotBlank
        }
        if (value.length() > max) {
            return false;
        }
        return pattern.matcher(value).matches();
    }
}
