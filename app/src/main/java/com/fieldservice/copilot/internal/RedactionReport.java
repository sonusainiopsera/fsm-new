package com.fieldservice.copilot.internal;

/**
 * Counts of substitutions made by the PII redactor, grouped by category.
 * Included in the interaction log for compliance evidence (WO-084).
 */
record RedactionReport(
        int customerNameSubstitutions,
        int contactNameSubstitutions,
        int emailSubstitutions,
        int phoneSubstitutions,
        int postcodeSubstitutions,
        int siteNameSubstitutions) {

    int total() {
        return customerNameSubstitutions + contactNameSubstitutions
                + emailSubstitutions + phoneSubstitutions
                + postcodeSubstitutions + siteNameSubstitutions;
    }
}
