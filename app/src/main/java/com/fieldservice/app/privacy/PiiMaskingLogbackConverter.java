package com.fieldservice.app.privacy;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback converter ({@code %maskedMsg}) that scrubs personal-data patterns from
 * the formatted log message before it is serialised.
 *
 * <p>This converter replaces:
 * <ul>
 *   <li>Email addresses</li>
 *   <li>International phone numbers</li>
 *   <li>UK postcodes</li>
 *   <li>High-precision coordinate pairs</li>
 *   <li>Bearer tokens and JWT fragments</li>
 * </ul>
 *
 * <p>The converter is self-contained (no Spring injection needed — Logback instantiates
 * converters outside the application context) and uses compiled {@link Pattern} constants
 * for minimal overhead.
 *
 * <p>Register in {@code logback-spring.xml}:
 * <pre>{@code
 * <conversionRule conversionWord="maskedMsg"
 *                 converterClass="com.fieldservice.app.privacy.PiiMaskingLogbackConverter"/>
 * }</pre>
 */
public class PiiMaskingLogbackConverter extends ClassicConverter {

    private static final String REDACTED = "[REDACTED]";

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    private static final Pattern PHONE =
            Pattern.compile("(?<![\\w.])(?:\\+?\\d[\\s\\-.]?){7,14}\\d(?![\\w.])");

    private static final Pattern UK_POSTCODE =
            Pattern.compile("\\b[A-Z]{1,2}\\d[\\dA-Z]?\\s*\\d[A-Z]{2}\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern COORDINATE_PAIR =
            Pattern.compile("-?\\d{1,3}\\.\\d{3,},\\s*-?\\d{1,3}\\.\\d{3,}");

    // Bearer token or JWT (3 base64url segments separated by dots, or "Bearer <token>")
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null || msg.isBlank()) return "";
        return scrub(msg);
    }

    static String scrub(String msg) {
        msg = BEARER_TOKEN.matcher(msg).replaceAll("Bearer " + REDACTED);
        msg = EMAIL.matcher(msg).replaceAll(REDACTED);
        msg = UK_POSTCODE.matcher(msg).replaceAll(REDACTED);
        msg = COORDINATE_PAIR.matcher(msg).replaceAll(REDACTED);
        msg = PHONE.matcher(msg).replaceAll(REDACTED);
        return msg;
    }
}
