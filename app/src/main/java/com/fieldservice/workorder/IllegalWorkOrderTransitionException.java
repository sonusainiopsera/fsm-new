package com.fieldservice.workorder;

import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.workorder.lifecycle.WorkOrderEvent;

import java.util.Set;

/**
 * Thrown when a caller requests an event that has no legal entry in the transition table
 * for the work order's current state.
 *
 * <p>Carries the stable code {@link WorkOrderErrorCodes#WORK_ORDER_ILLEGAL_TRANSITION}
 * plus diagnostic detail for the API layer to include in the 409 response body:
 * current state, requested event, and the set of events that ARE legal from the current state.
 */
public class IllegalWorkOrderTransitionException extends RuntimeException {

    private final WorkOrderState currentState;
    private final WorkOrderEvent requestedEvent;
    private final Set<WorkOrderEvent> legalEvents;

    public IllegalWorkOrderTransitionException(
            WorkOrderState currentState,
            WorkOrderEvent requestedEvent,
            Set<WorkOrderEvent> legalEvents) {
        super(String.format(
                "No transition defined: %s -[%s]-> ? (legal events from %s: %s)",
                currentState, requestedEvent, currentState, legalEvents));
        this.currentState  = currentState;
        this.requestedEvent = requestedEvent;
        this.legalEvents    = Set.copyOf(legalEvents);
    }

    public String getErrorCode() {
        return WorkOrderErrorCodes.WORK_ORDER_ILLEGAL_TRANSITION;
    }

    public WorkOrderState getCurrentState() {
        return currentState;
    }

    public WorkOrderEvent getRequestedEvent() {
        return requestedEvent;
    }

    /** Immutable set of events that ARE legal from {@link #getCurrentState()}. */
    public Set<WorkOrderEvent> getLegalEvents() {
        return legalEvents;
    }
}
