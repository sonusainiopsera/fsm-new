package com.fieldservice.portal.csat;

/** Delivery status of the survey notification. */
public enum CsatDeliveryStatus {
    /** Not yet attempted. */
    PENDING,
    /** Survey delivered via the in-app portal (no external provider needed). */
    IN_APP,
    /** Survey delivered via an external provider (email/SMS). */
    SENT,
    /** All delivery attempts failed; survey remains answerable in-app. */
    FAILED
}
