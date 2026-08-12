package com.fieldservice.workforce.application;

/**
 * Thrown by {@link PositionReportingService} when a position report is rejected.
 * Callers map the {@link Reason} to the appropriate HTTP status.
 */
public class PositionReportException extends RuntimeException {

    public enum Reason {
        /** capturedAt is stale (> 5 min) or in the future. Maps to 400. */
        STALE_TIMESTAMP,
        /** Technician has no EN_ROUTE or IN_PROGRESS job. Maps to 422. */
        NO_ACTIVE_JOB,
        /** Per-technician rate limit exceeded. Maps to 429. */
        RATE_LIMITED,
    }

    private final Reason reason;

    public PositionReportException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
