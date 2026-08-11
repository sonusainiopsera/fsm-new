package com.fieldservice.aigateway.api;

/**
 * Thrown when the user has reached their configured per-day AI interaction cap.
 * Maps to HTTP 429 with code AI_DAILY_LIMIT_REACHED and a Retry-After header.
 */
public class AiCapExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public AiCapExceededException(String userId, long retryAfterSeconds) {
        super("Daily AI interaction cap exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds until the daily counter rolls over at midnight UTC. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
