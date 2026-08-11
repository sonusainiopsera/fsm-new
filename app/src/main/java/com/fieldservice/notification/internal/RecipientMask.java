package com.fieldservice.notification.internal;

/**
 * Pure-function PII masking for notification recipients.
 *
 * <p>Rules:
 * <ul>
 *   <li>Email: first character + "***" + "@domain" — e.g. {@code j***@example.com}
 *   <li>Phone (digit-only after stripping punctuation): "***" + last two digits
 *   <li>Blank/null: the literal {@code [empty]}
 *   <li>Anything else: the literal {@code [masked]}
 * </ul>
 *
 * <p>The output is stable and non-reversible; it is safe for logs and persistence.
 * No framework dependency — testable as a pure unit test.
 */
public final class RecipientMask {

    private RecipientMask() {}

    public static String mask(String contact) {
        if (contact == null || contact.isBlank()) {
            return "[empty]";
        }
        int atIdx = contact.indexOf('@');
        if (atIdx > 0) {
            return contact.charAt(0) + "***" + contact.substring(atIdx);
        }
        String digits = contact.replaceAll("[^0-9]", "");
        if (digits.length() >= 2) {
            return "***" + digits.substring(digits.length() - 2);
        }
        return "[masked]";
    }
}
