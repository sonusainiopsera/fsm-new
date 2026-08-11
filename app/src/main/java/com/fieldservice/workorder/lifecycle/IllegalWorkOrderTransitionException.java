package com.fieldservice.workorder.lifecycle;

import com.fieldservice.workorder.WorkOrderErrorCodes;

import java.util.Set;

/** Thrown when an event is applied to a state with no matching table entry. */
public class IllegalWorkOrderTransitionException extends RuntimeException {

    private final WorkOrderState currentState;
    private final WorkOrderEvent requestedEvent;
    private final Set<WorkOrderEvent> legalEvents;

    public IllegalWorkOrderTransitionException(
            WorkOrderState currentState,
            WorkOrderEvent requestedEvent,
            Set<WorkOrderEvent> legalEvents) {
        super("Cannot apply event " + requestedEvent + " from state " + currentState
                + ". Legal events: " + legalEvents);
        this.currentState = currentState;
        this.requestedEvent = requestedEvent;
        this.legalEvents = Set.copyOf(legalEvents);
    }

    public WorkOrderState getCurrentState()       { return currentState; }
    public WorkOrderEvent getRequestedEvent()     { return requestedEvent; }
    public Set<WorkOrderEvent> getLegalEvents()   { return legalEvents; }
    public String getErrorCode()                  { return WorkOrderErrorCodes.ILLEGAL_TRANSITION; }
}
