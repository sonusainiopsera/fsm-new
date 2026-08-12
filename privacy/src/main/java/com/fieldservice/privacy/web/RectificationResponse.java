package com.fieldservice.privacy.web;

import com.fieldservice.privacy.api.FieldRectificationResult;

import java.util.List;

public record RectificationResponse(
        List<CorrectionResult> applied,
        List<CorrectionResult> skipped) {

    public record CorrectionResult(String entityName, String fieldName, String revisionId, String reason) {

        static CorrectionResult from(FieldRectificationResult result) {
            return new CorrectionResult(
                    result.entityName(), result.fieldName(), result.revisionId(), result.reason());
        }
    }

    static RectificationResponse from(List<FieldRectificationResult> applied,
                                       List<FieldRectificationResult> skipped) {
        return new RectificationResponse(
                applied.stream().map(CorrectionResult::from).toList(),
                skipped.stream().map(CorrectionResult::from).toList());
    }
}
