package com.fieldservice.portal.csat;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a CSAT survey for the portal survey list endpoint.
 */
public record CsatSurveyView(
        UUID            surveyId,
        UUID            workOrderId,
        Instant         issuedAt,
        Instant         expiresAt,
        CsatSurveyStatus status) {

    public static CsatSurveyView from(CsatSurvey s) {
        return new CsatSurveyView(
                s.getId(), s.getWorkOrderId(),
                s.getIssuedAt(), s.getExpiresAt(), s.getStatus());
    }
}
