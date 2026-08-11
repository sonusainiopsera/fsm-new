package com.fieldservice.platform.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link SafeText} constraints.
 *
 * <p>Rejects strings that:
 * <ul>
 *   <li>Exceed {@link SafeText#maxLength()} characters.</li>
 *   <li>Contain C0/C1 control characters (0x00–0x1F, 0x7F, 0x80–0x9F), null bytes,
 *       Unicode directional override characters (U+202A–U+202E, U+2066–U+2069),
 *       or zero-width characters (U+200B–U+200D, U+FEFF).</li>
 * </ul>
 *
 * <p>{@code null} values pass (use {@code @NotNull} separately if required).
 */
public class SafeTextValidator implements ConstraintValidator<SafeText, String> {

    private int maxLength;
    private boolean allowNewlines;

    @Override
    public void initialize(SafeText annotation) {
        this.maxLength = annotation.maxLength();
        this.allowNewlines = annotation.allowNewlines();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null) {
            return true;
        }
        if (value.length() > maxLength) {
            return false;
        }
        return !containsForbiddenCharacters(value);
    }

    private boolean containsForbiddenCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isForbidden(c)) {
                return true;
            }
        }
        return false;
    }

    private boolean isForbidden(char c) {
        // C0 controls (except tab, and newline if allowed)
        if (c < 0x20) {
            if (c == '\t') return false;
            if (allowNewlines && (c == '\n' || c == '\r')) return false;
            return true;
        }
        // DEL
        if (c == 0x7F) return true;
        // C1 controls
        if (c >= 0x80 && c <= 0x9F) return true;
        // Zero-width and directional override characters
        if (c >= 0x200B && c <= 0x200D) return true; // zero-width space/joiner
        if (c == 0x202A || c == 0x202B || c == 0x202C || c == 0x202D || c == 0x202E) return true; // dir overrides
        if (c >= 0x2066 && c <= 0x2069) return true; // directional isolates
        if (c == 0xFEFF) return true; // BOM / zero-width no-break space
        return false;
    }
}
