package com.fieldservice.notification.internal;

/**
 * Produces a stable, non-reversible token from a raw contact string.
 *
 * <p>Rules:
 * <ul>
 *   <li>Email: first char + "***" + "@" + domain  (e.g. "j***@example.com")</li>
 *   <li>Phone: "**" + last 2 digits  (e.g. "**42")</li>
 *   <li>Other: first 2 chars + "***"</li>
 *   <li>Blank/null: "***"</li>
 * </ul>
 *
 * <p>The token is deterministic for the same input and safe to persist in audit rows.
 */
final class RecipientMask {

    private RecipientMask() {}

    static String mask(String contact) {
        if (contact == null || contact.isBlank()) return "***";

        int atIdx = contact.indexOf('@');
        if (atIdx > 0) {
            String domain = contact.substring(atIdx);
            return contact.charAt(0) + "***" + domain;
        }

        String digits = contact.replaceAll("[^0-9]", "");
        if (digits.length() >= 4) {
            return "**" + digits.substring(digits.length() - 2);
        }

        return contact.substring(0, Math.min(2, contact.length())) + "***";
    }
}
