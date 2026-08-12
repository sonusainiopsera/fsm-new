package com.fieldservice.notification.domain;

/**
 * Categories of notifications emitted by the field service platform.
 * Each value corresponds to a domain event that may trigger user-facing alerts.
 */
public enum NotificationCategory {

    /** A new work order has been created in the system. */
    WORK_ORDER_CREATED,

    /** A work order has been assigned to a technician. */
    WORK_ORDER_ASSIGNED,

    /** An existing work order has been updated (e.g. description, priority). */
    WORK_ORDER_UPDATED,

    /** A work order has been completed by the assigned technician. */
    WORK_ORDER_COMPLETED,

    /** A work order's SLA deadline is approaching (configurable threshold). */
    SLA_AT_RISK,

    /** A work order's SLA deadline has been breached. */
    SLA_BREACH,

    /** A parts request has been raised against a work order. */
    PARTS_REQUEST,

    /** The assigned technician is en route to the job site. */
    TECHNICIAN_EN_ROUTE
}
