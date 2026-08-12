package com.fieldservice.portal.csat;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body returned after a successful CSAT response submission (HTTP 201).
 */
public record CsatResponseView(UUID surveyId, Instant submittedAt) {

    public static CsatResponseView from(CsatResponse r) {
        return new CsatResponseView(r.getSurveyId(), r.getSubmittedAt());
    }
}
