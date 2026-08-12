package com.fieldservice.privacy.api;

/**
 * Outcome of attempting a single field correction through a {@link SubjectDataRectifier}.
 *
 * <p>When {@code applied} is true, {@code revisionId} is the Envers revision number
 * written for the correction.  When {@code applied} is false, {@code reason} explains
 * why the correction was skipped (e.g. "field not found for subject", "value unchanged").
 */
public record FieldRectificationResult(
        String entityName,
        String fieldName,
        boolean applied,
        String revisionId,
        String reason) {

    public static FieldRectificationResult applied(String entityName, String fieldName, String revisionId) {
        return new FieldRectificationResult(entityName, fieldName, true, revisionId, null);
    }

    public static FieldRectificationResult skipped(String entityName, String fieldName, String reason) {
        return new FieldRectificationResult(entityName, fieldName, false, null, reason);
    }
}
