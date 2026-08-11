package com.fieldservice.portal.csat;

import java.util.UUID;

/** Thrown when a response is submitted after the survey's response window has closed. */
public class CsatWindowExpiredException extends RuntimeException {

    public CsatWindowExpiredException(UUID surveyId) {
        super("The response window for survey " + surveyId + " has expired.");
    }
}
