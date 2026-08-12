package com.fieldservice.dispatch.internal;

import com.fieldservice.workorder.domain.WorkOrderStatus;

/**
 * Thrown when a reassignment is attempted from a lifecycle state that does not permit it.
 * Maps to HTTP 409 with the current state named in the error body.
 */
public class ReassignmentNotPermittedException extends RuntimeException {

    private final WorkOrderStatus currentState;

    public ReassignmentNotPermittedException(WorkOrderStatus currentState) {
        super("Reassignment is not permitted from state " + currentState
                + ". Allowed states: ASSIGNED, EN_ROUTE, ON_HOLD, IN_PROGRESS.");
        this.currentState = currentState;
    }

    public WorkOrderStatus getCurrentState() { return currentState; }
}
