package com.fieldservice.portal.csat;

/** Lifecycle status of a {@link CsatSurvey}. */
public enum CsatSurveyStatus {
    /** Issued and awaiting a customer response. */
    PENDING,
    /** Response successfully submitted. */
    ANSWERED,
    /** Response window closed without a submission. Excluded from response-rate denominator. */
    EXPIRED
}
