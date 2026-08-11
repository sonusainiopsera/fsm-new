package com.fieldservice.workorder.domain;

/**
 * Lifecycle states for a work order. Transitions are enforced by the work order service
 * (TransitionGuard) — no direct state assignment is permitted from outside the aggregate.
 */
public enum WorkOrderStatus {
    OPEN,
    ASSIGNED,
    EN_ROUTE,
    IN_PROGRESS,
    ON_HOLD,
    COMPLETED,
    CANCELLED
}
