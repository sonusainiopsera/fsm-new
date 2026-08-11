package com.fieldservice.platform.api.exception;

/** Thrown when the AI provider is unavailable, times out, or the circuit is open. Maps to HTTP 503. */
public class AiUnavailableException extends RuntimeException {

    private final String operation;

    public AiUnavailableException(String operation, Throwable cause) {
        super("AI provider unavailable for operation: " + operation, cause);
        this.operation = operation;
    }

    public AiUnavailableException(String operation) {
        super("AI provider unavailable for operation: " + operation);
        this.operation = operation;
    }

    public String getOperation() { return operation; }
}
