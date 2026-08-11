package com.fieldservice.privacy.api;

import java.util.List;

/**
 * Response from {@code POST /api/v1/privacy/subjects/{subjectType}/{subjectId}/rectifications}.
 *
 * @param applied list of corrections successfully applied with their Envers revision IDs
 * @param skipped list of corrections that were skipped with reason codes
 */
public record RectifyResponse(
        List<AppliedCorrection> applied,
        List<SkippedCorrection> skipped
) {

    public record AppliedCorrection(String entityName, String fieldName, long revisionId) {}

    public record SkippedCorrection(String entityName, String fieldName, String reason) {}
}
