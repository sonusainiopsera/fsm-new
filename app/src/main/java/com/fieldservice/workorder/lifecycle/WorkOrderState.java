package com.fieldservice.workorder.lifecycle;

/** Lifecycle state vocabulary. Mirrors {@code WorkOrderStatus} for the state-machine layer. */
public enum WorkOrderState {
    NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD, COMPLETED, CLOSED, CANCELLED
}
