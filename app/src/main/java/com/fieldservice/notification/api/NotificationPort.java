package com.fieldservice.notification.api;

/**
 * Vendor-agnostic port for outbound notification delivery.
 *
 * <p>All delivery executes on the worker Spring profile only; no implementation of this
 * interface may be called on an api request thread.
 */
public interface NotificationPort {

    /**
     * Sends or durably degrades the notification described by {@code request}.
     *
     * @return {@link DeliveryOutcome#SENT} when the external provider accepted the message,
     *         {@link DeliveryOutcome#DEGRADED} when delivery fell back to an in-app
     *         notification row, {@link DeliveryOutcome#PERMANENT_FAILURE} when neither
     *         external delivery nor the in-app fallback could persist the alert.
     */
    DeliveryOutcome send(NotificationRequest request);
}
