package com.fieldservice.audit.internal;

import java.util.regex.Pattern;

/**
 * Applies PII masking to field values before they appear in search results, diffs, or exports.
 *
 * <p>Masking is driven by a static field-classification map: any field in that map has its
 * value replaced with a stable redaction token. This ensures newly added columns cannot
 * bypass masking — they must be explicitly classified first.
 *
 * <p>Fields not in the classification map are returned unmasked (they are non-PII
 * business fields such as work order state or SLA priority).
 */
final class PiiMaskingPolicy {

    static final String MASKED_TOKEN = "[REDACTED]";

    private static final Pattern EMAIL_PATTERN  =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN  =
            Pattern.compile("(?:\\+44\\s?|0)[1-9]\\d{8,9}|\\(?0\\d{4}\\)?\\s?\\d{3}\\s?\\d{3}");
    private static final Pattern GPS_PATTERN    =
            Pattern.compile("-?\\d{1,3}\\.\\d{4,}\\s*,\\s*-?\\d{1,3}\\.\\d{4,}");
    private static final Pattern POSTCODE_PATTERN =
            Pattern.compile("[A-Z]{1,2}\\d{1,2}[A-Z]?\\s*\\d[A-Z]{2}",
                    Pattern.CASE_INSENSITIVE);

    private PiiMaskingPolicy() {}

    /**
     * Masks a field value if the field is classified as containing PII.
     *
     * @param entityType  allow-listed entity type name
     * @param fieldName   column name from the entity's AUD table
     * @param value       raw string value (may be null)
     * @return masked token if PII, original value otherwise; null preserved as null
     */
    static String mask(String entityType, String fieldName, String value) {
        if (value == null) return null;

        AuditEntityAllowList.EntityMeta meta = AuditEntityAllowList.lookup(entityType).orElse(null);
        if (meta != null && meta.piiFields().contains(fieldName)) {
            return MASKED_TOKEN;
        }

        // Secondary defence: always scrub patterns even in non-classified fields.
        String scrubbed = value;
        scrubbed = EMAIL_PATTERN.matcher(scrubbed).replaceAll(MASKED_TOKEN);
        scrubbed = PHONE_PATTERN.matcher(scrubbed).replaceAll(MASKED_TOKEN);
        scrubbed = GPS_PATTERN.matcher(scrubbed).replaceAll(MASKED_TOKEN);
        scrubbed = POSTCODE_PATTERN.matcher(scrubbed).replaceAll(MASKED_TOKEN);
        return scrubbed;
    }

    /** Returns true if the field is classified as PII for the given entity type. */
    static boolean isPiiField(String entityType, String fieldName) {
        return AuditEntityAllowList.lookup(entityType)
                .map(m -> m.piiFields().contains(fieldName))
                .orElse(false);
    }
}
