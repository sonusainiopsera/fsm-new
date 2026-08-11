package com.fieldservice.portal.csat;

/** Lifecycle status of a CSAT survey. */
public enum CsatSurveyStatus {
    /** Survey issued; awaiting customer response. */
    PENDING,
    /** Customer submitted a response within the window. */
    ANSWERED,
    /** Response window closed without a submission. */
    EXPIRED
}
