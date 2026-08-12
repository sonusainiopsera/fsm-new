package com.fieldservice.portal.csat;

/** Delivery channel status for a {@link CsatSurvey} notification. */
public enum CsatDeliveryStatus {
    /** Delivery not yet attempted. */
    PENDING,
    /** External provider accepted the notification. */
    SENT,
    /** All delivery retries exhausted; survey still answerable in-app. */
    FAILED,
    /** No external provider configured; survey available in-app only. */
    IN_APP
}
