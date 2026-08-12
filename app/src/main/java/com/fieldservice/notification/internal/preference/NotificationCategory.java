package com.fieldservice.notification.internal.preference;

/**
 * Valid notification category values used in preference management.
 *
 * <p>These must exactly match the CHECK constraint in the {@code notification_preference}
 * table (V69 migration). Any addition requires a new migration to extend the CHECK.
 */
public enum NotificationCategory {
    WO_ASSIGNED,
    WO_REASSIGNED,
    WO_STATUS_CHANGE,
    SLA_RISK,
    SLA_BREACH,
    APPOINTMENT_CHANGED,
    CERTIFICATION_EXPIRING,
    CERTIFICATION_EXPIRED,
    WO_DUPLICATE_LINKED,
    WO_REJECTED,
    CLOSURE_SURVEY
}
