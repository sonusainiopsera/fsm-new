package com.fieldservice.platform.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class SafeTextValidator implements ConstraintValidator<SafeText, String> {

    // Allow printable Unicode letters, digits, spaces, and common punctuation;
    // block HTML injection vectors: < > & " and control characters.
    private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{N}\\p{P}\\p{Z}\\p{S}&&[^<>&\"]]*$");

    private int maxLength;

    @Override
    public void initialize(SafeText annotation) {
        this.maxLength = annotation.maxLength();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // null handled by @NotBlank
        }
        if (value.length() > maxLength) {
            return false;
        }
        return ALLOWED.matcher(value).matches();
    }
}
