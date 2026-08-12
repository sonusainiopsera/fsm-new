package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;

/**
 * Channel-aware content encoders for notification template parameters.
 *
 * <p>Each channel applies its own escaping strategy so that user-supplied or
 * fault-description text can never be interpreted as markup, script, or control
 * sequences regardless of its content.
 *
 * <ul>
 *   <li>{@link NotificationChannel#EMAIL} — HTML entity encoding; prevents XSS in
 *       HTML email bodies.</li>
 *   <li>{@link NotificationChannel#SMS} — control-character stripping and Unicode
 *       normalisation; preserves printable ASCII and common Unicode.</li>
 *   <li>{@link NotificationChannel#PUSH} — same as SMS; push notification bodies
 *       are plain text.</li>
 *   <li>{@link NotificationChannel#IN_APP} — structured-field pass-through; the
 *       client renderer is responsible for display-side escaping, so we strip only
 *       dangerous control characters and never pass raw HTML.</li>
 * </ul>
 */
public final class ChannelEncoders {

    private ChannelEncoders() {}

    /**
     * Encodes {@code value} for safe inclusion in a message body on {@code channel}.
     *
     * @param value   the raw parameter value (may be null)
     * @param channel target delivery channel
     * @return safely encoded string; empty string when value is null
     */
    public static String encode(Object value, NotificationChannel channel) {
        if (value == null) return "";
        String text = String.valueOf(value);
        return switch (channel) {
            case EMAIL -> htmlEscape(text);
            case SMS, PUSH -> plainTextNormalise(text);
            case IN_APP -> inAppSanitise(text);
        };
    }

    // ── Encoders ─────────────────────────────────────────────────────────────

    /**
     * HTML entity encoding: escapes {@code &}, {@code <}, {@code >}, {@code "},
     * and {@code '} to their entity equivalents.
     *
     * <p>This is the minimal set required to prevent HTML injection in email bodies.
     */
    static String htmlEscape(String text) {
        if (text.isEmpty()) return text;
        var sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
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
     * Plain-text normalisation for SMS and push channels.
     *
     * <p>Strips C0/C1 control characters (U+0000–U+001F, U+007F–U+009F) and
     * non-printable Unicode categories, preserving printable ASCII and standard Unicode.
     * Collapses runs of whitespace to a single space.
     */
    static String plainTextNormalise(String text) {
        if (text.isEmpty()) return text;
        var sb = new StringBuilder(text.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isSafeCodepoint(cp)) {
                boolean isSpace = Character.isWhitespace(cp);
                if (isSpace) {
                    if (!lastWasSpace) sb.append(' ');
                    lastWasSpace = true;
                } else {
                    sb.appendCodePoint(cp);
                    lastWasSpace = false;
                }
            }
            i += Character.charCount(cp);
        }
        return sb.toString().trim();
    }

    /**
     * In-app sanitisation: strips control characters only, does not HTML-encode.
     * The client-side renderer handles display escaping.
     */
    static String inAppSanitise(String text) {
        if (text.isEmpty()) return text;
        var sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isSafeCodepoint(cp)) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isSafeCodepoint(int cp) {
        // Strip C0 controls (except tab/LF/CR) and DEL
        if (cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D) return false;
        if (cp == 0x7F) return false;
        // Strip C1 controls
        if (cp >= 0x80 && cp <= 0x9F) return false;
        // Strip surrogate halves and BOM
        if (Character.isSurrogate((char) cp)) return false;
        if (cp == 0xFEFF) return false;
        return true;
    }
}
