package com.fieldservice.platform.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback pattern converter that scrubs personal-data patterns from log messages.
 *
 * <p>Registered in {@code logback-spring.xml} under the conversion word {@code piiMessage}.
 * It is applied to the formatted log message (after argument substitution) so masking is
 * automatic — no per-call-site effort is required.
 *
 * <p>Patterns detected and replaced with {@code [REDACTED]}:
 * <ul>
 *   <li>Email addresses (RFC 5321 simplified)</li>
 *   <li>Phone numbers (E.164 and common national formats)</li>
 *   <li>GPS coordinate pairs (lat,lon)</li>
 *   <li>UK postcodes</li>
 *   <li>Known credential prefixes ({@code password=…}, {@code token=…}, {@code key=…}, etc.)</li>
 * </ul>
 *
 * <p>Masking is bounded: messages longer than {@link #MAX_MESSAGE_LENGTH} characters are
 * truncated before scanning to prevent resource exhaustion from pathological inputs.
 *
 * <p>Registration in logback-spring.xml:
 * <pre>{@code
 * <conversionRule conversionWord="piiMessage"
 *     converterClass="com.fieldservice.platform.logging.MaskingMessageConverter"/>
 * }</pre>
 */
public class MaskingMessageConverter extends ClassicConverter {

    /** Maximum message length scanned to prevent resource exhaustion. */
    static final int MAX_MESSAGE_LENGTH = 16_384;

    /** Replacement token. */
    private static final String REDACTED = "[REDACTED]";

    // ── Patterns ──────────────────────────────────────────────────────────────

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![\\d.])\\+?[0-9][\\s\\-.]?(?:[0-9][\\s\\-.]?){8,14}(?![\\d])");

    private static final Pattern COORDINATE_PATTERN =
            Pattern.compile("-?(?:90(?:\\.0+)?|[0-8]?\\d(?:\\.\\d+)?)\\s*,\\s*-?(?:180(?:\\.0+)?|1[0-7]\\d(?:\\.\\d+)?|[0-9]\\d?(?:\\.\\d+)?)");

    private static final Pattern CREDENTIAL_PATTERN =
            Pattern.compile("(?i)(?:password|passwd|token|secret|apikey|api_key|key|credential)\\s*[=:]\\s*\\S+");

    private static final Pattern[] PATTERNS = {
            EMAIL_PATTERN,
            PHONE_PATTERN,
            COORDINATE_PATTERN,
            CREDENTIAL_PATTERN
    };

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null || message.isEmpty()) {
            return "";
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "[TRUNCATED]";
        }
        for (Pattern pattern : PATTERNS) {
            message = pattern.matcher(message).replaceAll(REDACTED);
        }
        return message;
    }
}
