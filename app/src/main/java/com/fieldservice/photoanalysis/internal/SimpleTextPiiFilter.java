package com.fieldservice.photoanalysis.internal;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight pattern-based PII redaction for photo analysis prompts.
 *
 * <p>Applied to any technician-supplied accompanying text (fault description, asset context)
 * before it is included in the AI vision request. Uses the same three pattern sweeps as the
 * WO-081 copilot PiiRedactor: email addresses, phone numbers, and UK postcodes.
 *
 * <p>Entity-name redaction (customer/site/contact literals) is not available here because
 * we do not load the full customer context for the photo endpoint. The pattern sweeps act as
 * a defence-in-depth layer for free-text PII.
 */
@Component
public class SimpleTextPiiFilter {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:(?:\\+|00)[0-9]{1,3}[\\s.-]?)?(?:\\([0-9]{1,4}\\)[\\s.-]?)?[0-9]{3,5}[\\s.-][0-9]{3,5}(?:[\\s.-][0-9]{3,5})?");
    private static final Pattern UK_POSTCODE_PATTERN = Pattern.compile(
            "\\b[A-Z]{1,2}[0-9][0-9A-Z]?\\s*[0-9][A-Z]{2}\\b");
    private static final Pattern CONTROL_CHARS = Pattern.compile(
            "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** Maximum characters accepted from accompanying text. */
    public static final int MAX_CHARS = 2000;

    /** Redacts PII patterns from the input text and caps at {@link #MAX_CHARS}. */
    public RedactedText redact(String text) {
        if (text == null || text.isBlank()) {
            return new RedactedText("", 0, 0, 0);
        }

        String cleaned = CONTROL_CHARS.matcher(text).replaceAll("");
        String capped   = cleaned.length() > MAX_CHARS
                ? cleaned.substring(0, MAX_CHARS) + "[…]"
                : cleaned;

        int[] counts = {0, 0, 0}; // [email, phone, postcode]

        String result = replaceAll(capped,  EMAIL_PATTERN,        "EMAIL_REDACTED",    counts, 0);
        result        = replaceAll(result,  PHONE_PATTERN,        "PHONE_REDACTED",    counts, 1);
        result        = replaceAll(result,  UK_POSTCODE_PATTERN,  "POSTCODE_REDACTED", counts, 2);

        return new RedactedText(result, counts[0], counts[1], counts[2]);
    }

    private static String replaceAll(String text, Pattern p, String replacement, int[] counts, int idx) {
        Matcher m  = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean found  = false;
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            found = true;
        }
        m.appendTail(sb);
        if (found) counts[idx]++;
        return sb.toString();
    }

    public record RedactedText(
            String text,
            int emailsRedacted,
            int phonesRedacted,
            int postcodesRedacted) {

        public String toSummaryJson() {
            return "{\"email\":" + emailsRedacted
                    + ",\"phone\":" + phonesRedacted
                    + ",\"postcode\":" + postcodesRedacted + "}";
        }
    }
}
