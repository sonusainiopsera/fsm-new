package com.fieldservice.workorder.domain;

/**
 * Lifecycle states for a work order. Transitions are enforced by the work order service
 * (TransitionGuard) — no direct state assignment is permitted from outside the aggregate.
 * Values mirror the work_order.state CHECK constraint in the database schema.
 */
public enum WorkOrderStatus {
    NEW,
    ASSIGNED,
    EN_ROUTE,
    IN_PROGRESS,
    ON_HOLD,
    COMPLETED,
    CLOSED,
    CANCELLED;

    /**
     * Returns the set of states that represent an open (non-terminal) work order.
     * Analytics and backlog queries use this vocabulary to count active jobs without
     * hardcoding a state list that could diverge from the lifecycle definition.
     */
    public static java.util.Set<WorkOrderStatus> openStates() {
        return java.util.EnumSet.of(NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD);
    }
}
