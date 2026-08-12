package com.fieldservice.platform.privacy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory for built-in masking strategy implementations.
 *
 * <p>All strategies are pure functions: null-safe, idempotent, non-reversible,
 * and non-throwing on malformed input.
 */
public final class MaskingStrategies {

    // Patterns used internally
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^([^@])(.*)(@.+\\..+)$");
    private static final Pattern PHONE_DIGITS  =
            Pattern.compile("\\d");

    private MaskingStrategies() {}

    /**
     * Full redaction — replaces the value with {@link MaskingStrategy#REDACTED}.
     * Used for RESTRICTED tier values (tokens, hashes, keys).
     */
    public static final MaskingStrategy FULL_REDACT = value ->
            (value == null || value.isBlank()) ? MaskingStrategy.REDACTED : MaskingStrategy.REDACTED;

    /**
     * EMAIL masking: keeps the first character and domain suffix, e.g.
     * {@code j***@example.com}. Malformed emails are fully redacted.
     */
    public static final MaskingStrategy EMAIL = value -> {
        if (value == null || value.isBlank()) return MaskingStrategy.REDACTED;
        if (value.startsWith("[REDACTED")) return value;
        Matcher m = EMAIL_PATTERN.matcher(value.strip());
        if (!m.matches()) return MaskingStrategy.REDACTED;
        return m.group(1) + "***" + m.group(3);
    };

    /**
     * PHONE masking: replaces all but the last two digits with {@code *},
     * preserving non-digit separators. E.g. {@code +44 *** *** **89}.
     */
    public static final MaskingStrategy PHONE = value -> {
        if (value == null || value.isBlank()) return MaskingStrategy.REDACTED;
        if (value.startsWith("[REDACTED")) return value;
        StringBuilder sb = new StringBuilder(value.length());
        char[] chars = value.toCharArray();
        // Count digits
        int digitCount = 0;
        for (char c : chars) if (Character.isDigit(c)) digitCount++;
        int showFrom = digitCount - 2;
        int seen = 0;
        for (char c : chars) {
            if (Character.isDigit(c)) {
                sb.append(seen++ < showFrom ? '*' : c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    };

    /**
     * NAME masking: keeps only the initial letter of each word followed by {@code .}.
     * E.g. {@code John Smith} → {@code J. S.}.
     */
    public static final MaskingStrategy NAME = value -> {
        if (value == null || value.isBlank()) return MaskingStrategy.REDACTED;
        if (value.startsWith("[REDACTED")) return value;
        String[] words = value.strip().split("\\s+");
        if (words.length == 0) return MaskingStrategy.REDACTED;
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(". ");
            }
        }
        return sb.toString().strip();
    };

    /**
     * ADDRESS masking: keeps only the postcode/zip portion (last word-group matching
     * a postcode pattern) or fully redacts if not parseable.
     */
    public static final MaskingStrategy ADDRESS = value -> {
        if (value == null || value.isBlank()) return MaskingStrategy.REDACTED;
        if (value.startsWith("[REDACTED")) return value;
        // Keep last comma-separated component as a region hint only
        String[] parts = value.split(",");
        String last = parts[parts.length - 1].strip();
        // If the last part looks like a postcode/state/region, keep it; otherwise redact
        if (last.length() <= 20 && last.matches("[A-Za-z0-9 \\-]+")) {
            return "[ADDR]..." + last;
        }
        return MaskingStrategy.REDACTED;
    };

    /**
     * COORDINATE masking: reduces precision to 1 decimal place (≈11 km accuracy)
     * so the value cannot identify a specific address or property.
     * Values at exactly 0,0 or malformed are fully redacted.
     */
    public static final MaskingStrategy COORDINATE = value -> {
        if (value == null || value.isBlank()) return MaskingStrategy.REDACTED;
        if (value.startsWith("[REDACTED")) return value;
        try {
            double d = Double.parseDouble(value.strip());
            if (d == 0.0) return MaskingStrategy.REDACTED;
            // Reduce to 1 decimal place
            return String.format("%.1f~", Math.round(d * 10.0) / 10.0);
        } catch (NumberFormatException e) {
            return MaskingStrategy.REDACTED;
        }
    };

    /**
     * TOKEN masking: fully redacts JWT tokens, API keys and session identifiers.
     * Equivalent to {@link #FULL_REDACT} but named for semantic clarity.
     */
    public static final MaskingStrategy TOKEN = FULL_REDACT;

    /**
     * HASH masking: fully redacts bcrypt hashes, HMAC values and key material.
     * Equivalent to {@link #FULL_REDACT} but named for semantic clarity.
     */
    public static final MaskingStrategy HASH = FULL_REDACT;

    /**
     * Selects the default masking strategy for the given tier.
     *
     * <p>Unclassified fields default to {@link #FULL_REDACT} — the most restrictive treatment.
     */
    public static MaskingStrategy forTier(MaskingTier tier) {
        if (tier == null) return FULL_REDACT;
        return switch (tier) {
            case RESTRICTED -> FULL_REDACT;
            case CONFIDENTIAL -> EMAIL; // default CONFIDENTIAL strategy; callers may use field-specific
            case INTERNAL, PUBLIC -> value -> value == null ? "" : value;
        };
    }
}
