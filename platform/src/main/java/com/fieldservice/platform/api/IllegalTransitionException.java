package com.fieldservice.platform.api;

/**
 * Thrown when a lifecycle transition is invalid for the current state.
 * Maps to HTTP 409 with code {@link ErrorCode#ILLEGAL_TRANSITION}.
 */
public class IllegalTransitionException extends ApiException {

    public IllegalTransitionException(String entityType, Object currentState, Object requestedTransition) {
        super(ErrorCode.ILLEGAL_TRANSITION,
                entityType + " cannot transition from " + currentState + " via " + requestedTransition);
    }

    public IllegalTransitionException(String message) {
        super(ErrorCode.ILLEGAL_TRANSITION, message);
    }
}
