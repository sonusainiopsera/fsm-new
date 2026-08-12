package com.fieldservice.audit.internal;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Applies PII masking rules to projected field values in audit results and exports.
 *
 * <p>Every field in {@link AuditEntityMetadata#PII_FIELDS} for the given entity type
 * is replaced with a stable sentinel. The mask is applied before the value leaves the
 * service layer so it cannot be bypassed by adding a column to a projection.
 */
@Component
class PiiMaskingPolicy {

    static final String MASK_SENTINEL = "[MASKED]";

    /**
     * Returns {@link #MASK_SENTINEL} if {@code fieldName} is classified as PII for
     * the given entity type; otherwise returns the original value unchanged.
     *
     * @param entityType allow-listed entity type name
     * @param fieldName  field name as it appears in the projection
     * @param value      the original field value
     * @return masked or original value
     */
    Object maskIfPii(String entityType, String fieldName, Object value) {
        if (value == null) return null;
        Set<String> piiFields = AuditEntityMetadata.PII_FIELDS.getOrDefault(entityType, Set.of());
        return piiFields.contains(fieldName) ? MASK_SENTINEL : value;
    }

    /**
     * Returns true if the field is classified as PII for the given entity type.
     */
    boolean isPii(String entityType, String fieldName) {
        return AuditEntityMetadata.PII_FIELDS
                .getOrDefault(entityType, Set.of())
                .contains(fieldName);
    }
}
