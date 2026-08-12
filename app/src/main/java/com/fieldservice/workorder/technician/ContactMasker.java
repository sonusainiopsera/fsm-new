package com.fieldservice.workorder.technician;

/**
 * Masking utility for confidential contact data on mobile devices.
 *
 * <p>Exposes only the last four digits of a phone number so a technician can
 * visually identify the right contact without storing the full number on a device
 * that may be lost or inspected. Email is omitted entirely.
 */
final class ContactMasker {

    private ContactMasker() {}

    /**
     * Returns a masked phone string exposing only the last four digits.
     *
     * <p>Strips all non-digit characters, then returns {@code "****" + lastFour}.
     * Returns {@code null} for a null or blank input, and {@code "****"} when
     * fewer than four digits are present.
     *
     * @param phone raw phone number (may include spaces, dashes, parentheses)
     * @return masked form e.g. {@code "****1234"}, or {@code null} if absent
     */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }
}
