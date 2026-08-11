package com.fieldservice.sla;

/**
 * Thrown when no active SLA policy row can be resolved for the requested priority at a
 * given instant. Maps to HTTP 422 via the platform GlobalExceptionHandler (BusinessGuardException).
 *
 * <p>This is deliberately a checked-style runtime exception — callers must not swallow it
 * silently or substitute a default deadline. Missing policy must fail loudly.
 */
public class SlaPolicyUnavailableException extends RuntimeException {

    private final String priority;

    public SlaPolicyUnavailableException(String priority) {
        super("No active SLA policy found for priority: " + priority);
        this.priority = priority;
    }

    public String getPriority() { return priority; }
}
