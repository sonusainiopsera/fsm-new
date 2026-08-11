package com.fieldservice.aigateway.internal;

import java.util.regex.Pattern;

/**
 * Scans free-text strings for embedded personal-data patterns and replaces matches
 * with {@code [REDACTED]}.
 *
 * <p>Designed for AI-egress redaction: fault descriptions and copilot prompts may contain
 * customer names, addresses, phone numbers, or email addresses embedded as natural language.
 * This scrubber provides a best-effort, regex-driven backstop; structured field masking via
 * {@link com.fieldservice.platform.masking.PiiMasker} remains the primary control.
 *
 * <p>The scan is bounded at {@link #MAX_INPUT_LENGTH} characters to prevent resource
 * exhaustion on pathological inputs. Text exceeding this limit is truncated and a
 * {@code [TRUNCATED]} suffix is appended before scanning.
 */
class FreeTextScrubber {

    /** Maximum characters scanned per call. */
    static final int MAX_INPUT_LENGTH = 65_536;

    private static final String REDACTED = "[REDACTED]";

    // ── Detection patterns ─────────────────────────────────────────────────

    private static final Pattern EMAIL =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE =
            Pattern.compile("(?<![\\d.])\\+?[0-9][\\s\\-.]?(?:[0-9][\\s\\-.]?){8,14}(?![\\d])");

    private static final Pattern COORDINATE =
            Pattern.compile("-?(?:90(?:\\.0+)?|[0-8]?\\d(?:\\.\\d+)?)\\s*,\\s*"
                    + "-?(?:180(?:\\.0+)?|1[0-7]\\d(?:\\.\\d+)?|[0-9]\\d?(?:\\.\\d+)?)");

    /** UK postcode (e.g. SW1A 2AA). */
    private static final Pattern POSTCODE =
            Pattern.compile("\\b[A-Z]{1,2}[0-9][0-9A-Z]?\\s*[0-9][A-Z]{2}\\b");

    /** Credential key-value pairs in log-style text. */
    private static final Pattern CREDENTIAL =
            Pattern.compile("(?i)(?:password|passwd|token|secret|apikey|api_key|key|credential)\\s*[=:]\\s*\\S+");

    private static final Pattern[] PATTERNS = {EMAIL, PHONE, COORDINATE, POSTCODE, CREDENTIAL};

    /**
     * Scrubs personal-data patterns from the given text.
     *
     * @param text free-text input; may be {@code null}
     * @return scrubbed text, or {@code null} if the input was {@code null}
     */
    String scrub(String text) {
        if (text == null) return null;
        if (text.isEmpty()) return text;

        String working = text.length() > MAX_INPUT_LENGTH
                ? text.substring(0, MAX_INPUT_LENGTH) + "[TRUNCATED]"
                : text;

        for (Pattern pattern : PATTERNS) {
            working = pattern.matcher(working).replaceAll(REDACTED);
        }
        return working;
    }
}
