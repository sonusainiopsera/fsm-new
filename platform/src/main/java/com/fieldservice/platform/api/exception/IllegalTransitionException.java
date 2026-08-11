package com.fieldservice.platform.api.exception;

/** Thrown when a lifecycle state transition is not permitted. Maps to HTTP 409. */
public class IllegalTransitionException extends RuntimeException {
    private final String fromState;
    private final String toState;

    public IllegalTransitionException(String resourceType, String fromState, String toState) {
        super(resourceType + " cannot transition from " + fromState + " to " + toState);
        this.fromState = fromState;
        this.toState   = toState;
    }

    public String getFromState() { return fromState; }
    public String getToState()   { return toState; }
}
