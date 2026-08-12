package com.fieldservice.portal.csat;

import java.util.UUID;

/** Thrown when a duplicate response is submitted for an already-answered survey. */
public class CsatAlreadyAnsweredException extends RuntimeException {

    private final UUID surveyId;

    public CsatAlreadyAnsweredException(UUID surveyId) {
        super("Survey " + surveyId + " has already been answered.");
        this.surveyId = surveyId;
    }

    public UUID getSurveyId() { return surveyId; }
}
