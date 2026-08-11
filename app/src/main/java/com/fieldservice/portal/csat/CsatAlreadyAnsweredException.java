package com.fieldservice.portal.csat;

import java.util.UUID;

/** Thrown when a response is submitted to a survey that has already been answered. */
public class CsatAlreadyAnsweredException extends RuntimeException {

    public CsatAlreadyAnsweredException(UUID surveyId) {
        super("Survey " + surveyId + " has already been answered.");
    }
}
