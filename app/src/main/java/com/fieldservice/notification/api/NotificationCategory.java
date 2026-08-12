package com.fieldservice.notification.api;

/**
 * Allow-listed notification categories.
 *
 * <p>Each category corresponds to a class of domain event that triggers fan-out.
 * The string names are stored in {@code notification_preference.category} and
 * validated via a database CHECK constraint — extending this enum requires a
 * matching Flyway migration to widen the CHECK.
 *
 * <p>Used as the vocabulary for per-user channel preference resolution.
 */
public enum NotificationCategory {

    /** Technician assigned or reassigned to a work order. */
    WORK_ORDER_ASSIGNMENT,

    /** SLA at-risk flag raised or SLA breach recorded (dispatcher / manager). */
    SLA_ALERT,

    /** Customer-visible work order state change. */
    CUSTOMER_STATUS_UPDATE,

    /** CSAT survey issued after work order closure. */
    CSAT_SURVEY,

    /** Confirmed appointment window changed. */
    APPOINTMENT_UPDATE,

    /** Duplicate work order linked to the customer's existing request. */
    PORTAL_REQUEST_UPDATE,

    /** Technician certification expiring soon or already expired. */
    CERTIFICATION_ALERT,
}
