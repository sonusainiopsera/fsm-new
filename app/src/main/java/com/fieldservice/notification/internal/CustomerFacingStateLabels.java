package com.fieldservice.notification.internal;

import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for customer-facing work order status labels (WO-196, AC-8).
 *
 * <p>Internal lifecycle state names (e.g. {@code IN_PROGRESS}, {@code ON_HOLD}) must never
 * be surfaced in customer-channel notifications. This class maps each internal state to a
 * customer-appropriate label so no consumer can accidentally expose an internal name.
 *
 * <p>States not present in the map are intentionally not customer-visible and should not
 * trigger a customer notification. Consumers must call {@link #isCustomerVisible(String)}
 * before attempting to render.
 */
public final class CustomerFacingStateLabels {

    private CustomerFacingStateLabels() {}

    /**
     * Internal-to-customer label map.
     *
     * <p>Keyed on {@code WorkOrderState} name strings to avoid a compile-time coupling
     * to the workorder module (AC-6: no dependency on workorder internals).
     */
    private static final Map<String, String> LABELS = Map.of(
            "ASSIGNED",     "Technician assigned — we will be with you soon",
            "EN_ROUTE",     "Technician is on the way",
            "IN_PROGRESS",  "Work is in progress at your site",
            "ON_HOLD",      "Work is temporarily on hold — we will update you shortly",
            "COMPLETED",    "Work has been completed",
            "CLOSED",       "Your service request is now closed",
            "CANCELLED",    "Your service request has been cancelled"
    );

    /**
     * Returns true if the state should trigger a customer-facing notification.
     *
     * <p>NEW is intentionally excluded — customers do not receive a notification when
     * a work order is created internally; they receive one on ASSIGNED.
     */
    public static boolean isCustomerVisible(String workOrderState) {
        return LABELS.containsKey(workOrderState);
    }

    /**
     * Returns the customer-facing label for the given state, or empty if not customer-visible.
     */
    public static Optional<String> labelFor(String workOrderState) {
        return Optional.ofNullable(LABELS.get(workOrderState));
    }
}
