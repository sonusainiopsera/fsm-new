package com.fieldservice.workorder.application;

/**
 * Utility for masking contact details before they leave the server for a mobile device.
 *
 * <p>Phone masking: strips non-digit characters, then returns only the last four digits
 * zero-padded to exactly four, prefixed with "****". Email is omitted entirely.
 */
public final class ContactMaskingHelper {

    private ContactMaskingHelper() {}

    /**
     * Returns a masked phone string containing only the last four digits.
     *
     * @param phone raw phone value (may contain spaces, dashes, parentheses)
     * @return {@code "****NNNN"} or {@code null} if phone is null/blank/insufficient digits
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.length() < 4) {
            return "****";
        }
        return "****" + digits.substring(digits.length() - 4);
    }
}
