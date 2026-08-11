package com.fieldservice.platform.exception;

/**
 * Thrown when a state-machine transition is attempted that is not in the
 * allowed transition table.
 *
 * <p>Maps to HTTP 409.
 */
public class IllegalTransitionException extends RuntimeException {

    private final String fromState;
    private final String toState;

    public IllegalTransitionException(String fromState, String toState) {
        super("Transition from " + fromState + " to " + toState + " is not permitted.");
        this.fromState = fromState;
        this.toState = toState;
    }

    public IllegalTransitionException(Enum<?> fromState, Enum<?> toState) {
        this(fromState.name(), toState.name());
    }

    public String getFromState() { return fromState; }
    public String getToState() { return toState; }
}
