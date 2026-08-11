package com.fieldservice.copilot.api;

/**
 * Summary of PII substitutions performed by the redactor, for the interaction log (WO-084).
 *
 * <p>Counts only — no original values are retained.
 *
 * @param customerNameCount     number of customer name occurrences replaced
 * @param contactNameCount      number of contact name occurrences replaced
 * @param phoneCount            number of phone-pattern occurrences replaced
 * @param emailCount            number of email-pattern occurrences replaced
 * @param addressCount          number of address literal occurrences replaced
 * @param postcodeCount         number of postcode occurrences replaced
 * @param totalSubstitutions    total across all categories
 */
public record RedactionReport(
        int customerNameCount,
        int contactNameCount,
        int phoneCount,
        int emailCount,
        int addressCount,
        int postcodeCount
) {
    public int totalSubstitutions() {
        return customerNameCount + contactNameCount + phoneCount + emailCount + addressCount + postcodeCount;
    }

    public static RedactionReport empty() {
        return new RedactionReport(0, 0, 0, 0, 0, 0);
    }
}
