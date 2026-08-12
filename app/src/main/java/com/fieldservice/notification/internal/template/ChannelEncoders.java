package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;

/**
 * Channel-aware output encoders for notification body content.
 *
 * <p>Rules:
 * <ul>
 *   <li>EMAIL: HTML-escape special characters so user-supplied values cannot inject markup.</li>
 *   <li>SMS / PUSH: strip any HTML tags and normalise whitespace to plain text.</li>
 *   <li>IN_APP: pass through unmodified — the client-side renderer is responsible for escaping.</li>
 * </ul>
 *
 * <p>No user-supplied text reaches the template before encoding; encoding is applied to
 * parameter values before they are substituted into the template body.
 */
public final class ChannelEncoders {

    private ChannelEncoders() {}

    /**
     * Encodes a parameter value for safe insertion into a template targeting {@code channel}.
     *
     * @param value   raw parameter value (may contain HTML, special characters, emoji)
     * @param channel target channel
     * @return encoded value safe for the target channel
     */
    public static String encode(String value, NotificationChannel channel) {
        if (value == null) {
            return "";
        }
        return switch (channel) {
            case EMAIL   -> htmlEscape(value);
            case SMS     -> toPlainText(value);
            case PUSH    -> toPlainText(value);
            case IN_APP  -> value; // client escapes on render
        };
    }

    /**
     * HTML-escapes the five XML/HTML special characters.
     * Apostrophe is escaped to {@code &#x27;} for attribute-value safety.
     */
    static String htmlEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&'  -> sb.append("&amp;");
                case '<'  -> sb.append("&lt;");
                case '>'  -> sb.append("&gt;");
                case '"'  -> sb.append("&quot;");
                case '\'' -> sb.append("&#x27;");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Strips HTML tags and normalises whitespace to a single space for SMS/PUSH.
     */
    static String toPlainText(String value) {
        // Strip all HTML tags
        String stripped = value.replaceAll("<[^>]*>", "");
        // Decode common HTML entities
        stripped = stripped
                .replace("&amp;",  "&")
                .replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&nbsp;", " ");
        // Normalise whitespace
        return stripped.replaceAll("\\s+", " ").trim();
    }
}
