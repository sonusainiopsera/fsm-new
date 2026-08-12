package com.fieldservice.photoanalysis.internal;

import java.util.regex.Pattern;

/**
 * Standalone pattern-based PII scrubber for vision prompt text (WO-181 AC-4).
 *
 * <p>Mirrors the patterns in {@code aigateway.internal.FreeTextScrubber} (package-private).
 * Cannot import that class from here, so the patterns are reproduced here with the same
 * redaction token vocabulary to maintain consistency in audit logs.
 *
 * <p>Usage: call {@link #scrub(String)} before including any technician-supplied context
 * text in the vision prompt.
 */
final class PromptTextScrubber {

    private PromptTextScrubber() {}

    static final int MAX_INPUT_LENGTH = 65_536;

    private static final String REDACTED_EMAIL      = "[EMAIL]";
    private static final String REDACTED_PHONE      = "[PHONE]";
    private static final String REDACTED_COORDINATE = "[COORDINATE]";
    private static final String REDACTED_POSTCODE   = "[POSTCODE]";
    private static final String REDACTED_CREDENTIAL = "[CREDENTIAL]";

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?:\\+?\\d[\\s\\-().]{0,3}){7,14}\\d");

    private static final Pattern COORDINATE_PATTERN =
            Pattern.compile("-?\\d{1,3}\\.\\d{3,},?\\s*-?\\d{1,3}\\.\\d{3,}");

    private static final Pattern UK_POSTCODE_PATTERN =
            Pattern.compile("\\b[A-Z]{1,2}\\d[\\dA-Z]?\\s?\\d[A-Z]{2}\\b");

    private static final Pattern CREDENTIAL_PATTERN =
            Pattern.compile("(?i)(?:password|passwd|secret|token|api[_\\-]?key)\\s*[:=]\\s*\\S+");

    /**
     * Scrubs PII patterns from the input text.
     *
     * @param text text to scrub; returns {@code null} if {@code text} is {@code null}
     * @return scrubbed text with PII replaced by tokens; truncated at {@link #MAX_INPUT_LENGTH}
     */
    static String scrub(String text) {
        if (text == null) return null;
        String t = text.length() > MAX_INPUT_LENGTH ? text.substring(0, MAX_INPUT_LENGTH) : text;
        t = CREDENTIAL_PATTERN.matcher(t).replaceAll(REDACTED_CREDENTIAL);
        t = EMAIL_PATTERN.matcher(t).replaceAll(REDACTED_EMAIL);
        t = COORDINATE_PATTERN.matcher(t).replaceAll(REDACTED_COORDINATE);
        t = UK_POSTCODE_PATTERN.matcher(t).replaceAll(REDACTED_POSTCODE);
        t = PHONE_PATTERN.matcher(t).replaceAll(REDACTED_PHONE);
        return t;
    }
}
