package com.fieldservice.sla;

/**
 * Thrown when no active SLA policy row can be resolved for a priority at a given instant.
 *
 * <p>Maps to HTTP 422 (Unprocessable Entity) via the global exception handler.
 * No work order is persisted and no default deadline is invented when this is thrown.
 */
public class SlaPolicyUnavailableException extends RuntimeException {

    private final String priority;

    public SlaPolicyUnavailableException(String priority) {
        super("No active SLA policy found for priority: " + priority);
        this.priority = priority;
    }

    public String getPriority() {
        return priority;
    }
}
