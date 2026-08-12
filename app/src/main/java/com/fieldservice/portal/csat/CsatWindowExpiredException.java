package com.fieldservice.portal.csat;

import java.util.UUID;

/** Thrown when a response is submitted after the survey's response window has closed. */
public class CsatWindowExpiredException extends RuntimeException {

    private final UUID surveyId;

    public CsatWindowExpiredException(UUID surveyId) {
        super("The response window for survey " + surveyId
              + " has expired. No further responses can be submitted.");
        this.surveyId = surveyId;
    }

    public UUID getSurveyId() { return surveyId; }
}
