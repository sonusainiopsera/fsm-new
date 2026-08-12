package com.fieldservice.platform.privacy;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Regex-based scrubber for unstructured text (AI prompts, fault descriptions, log messages).
 *
 * <p>Detects and replaces personal-data patterns that may be embedded in free text.
 * The scrubber is bounded to {@link #MAX_INPUT_BYTES} characters to prevent resource
 * exhaustion on very large payloads.
 *
 * <p>All replacements are idempotent: an already-scrubbed string passes through unchanged.
 */
@Component
public class FreeTextScrubber {

    /** Maximum number of characters processed; input is truncated at this boundary. */
    static final int MAX_INPUT_BYTES = 51_200;

    private static final String EMAIL_REPL    = "[EMAIL-REDACTED]";
    private static final String PHONE_REPL    = "[PHONE-REDACTED]";
    private static final String POSTCODE_REPL = "[POSTCODE-REDACTED]";
    private static final String COORD_REPL    = "[COORD-REDACTED]";

    // Email: local@domain.tld (simplified; covers most practical cases)
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    // International phone: optional +, country code, digits and separators, at least 7 digits
    private static final Pattern PHONE =
            Pattern.compile("(?:\\+?\\d[\\s\\-.]?){7,15}\\d");

    // UK postcode (also matches common formats e.g. EC1A 1BB, SW1A 2AA)
    private static final Pattern UK_POSTCODE =
            Pattern.compile("\\b[A-Z]{1,2}\\d[\\dA-Z]?\\s*\\d[A-Z]{2}\\b",
                    Pattern.CASE_INSENSITIVE);

    // Coordinate pair: two signed decimals separated by comma/space (lat,lon)
    private static final Pattern COORDINATE_PAIR =
            Pattern.compile("-?\\d{1,3}\\.\\d{3,},\\s*-?\\d{1,3}\\.\\d{3,}");

    /**
     * Scrubs known personal-data patterns from {@code text}, returning the sanitised string.
     *
     * @param text input text (may be null)
     * @return scrubbed text; never null
     */
    public String scrub(String text) {
        if (text == null || text.isBlank()) return "";

        // Guard: truncate oversized input
        String working = text.length() > MAX_INPUT_BYTES ? text.substring(0, MAX_INPUT_BYTES) : text;

        // Apply patterns in specificity order (most specific first to avoid double-scrubbing)
        working = EMAIL.matcher(working).replaceAll(EMAIL_REPL);
        working = UK_POSTCODE.matcher(working).replaceAll(POSTCODE_REPL);
        working = COORDINATE_PAIR.matcher(working).replaceAll(COORD_REPL);
        working = PHONE.matcher(working).replaceAll(PHONE_REPL);

        return working;
    }
}
