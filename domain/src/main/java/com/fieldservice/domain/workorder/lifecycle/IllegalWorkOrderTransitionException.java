package com.fieldservice.domain.workorder.lifecycle;

import com.fieldservice.domain.workorder.WorkOrderState;

import java.util.Set;

/**
 * Thrown when an event is applied to a work order whose current state has no
 * outbound transition for that event.
 *
 * <p>Carries the current state, the refused event, and the set of events that
 * ARE legal from the current state so the caller can surface an actionable message.
 * The API layer maps this to HTTP 409 with code {@link WorkOrderErrorCodes#WORK_ORDER_ILLEGAL_TRANSITION}.
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
                "Illegal transition: state=%s event=%s legalEvents=%s",
                currentState, requestedEvent, legalEvents));
        this.currentState = currentState;
        this.requestedEvent = requestedEvent;
        this.legalEvents = Set.copyOf(legalEvents);
    }

    public String getCode() {
        return WorkOrderErrorCodes.WORK_ORDER_ILLEGAL_TRANSITION;
    }

    public WorkOrderState getCurrentState() {
        return currentState;
    }

    public WorkOrderEvent getRequestedEvent() {
        return requestedEvent;
    }

    public Set<WorkOrderEvent> getLegalEvents() {
        return legalEvents;
    }
}
