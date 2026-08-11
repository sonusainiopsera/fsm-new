package com.fieldservice.domain.workorder;

/**
 * Lifecycle states for a work order.
 *
 * <p>Legal transitions are enforced by the transition guard in the WorkOrder service.
 * An illegal transition attempt returns HTTP 409.
 */
public enum WorkOrderState {
    OPEN,
    ASSIGNED,
    EN_ROUTE,
    IN_PROGRESS,
    ON_HOLD,
    COMPLETED,
    CANCELLED
}
