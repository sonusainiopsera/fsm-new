package com.fieldservice.platform.api.exception;

/** Thrown when a user has exhausted their daily AI interaction quota. Maps to HTTP 429. */
public class AiCapExceededException extends RuntimeException {

    private final String userId;
    private final long retryAfterSeconds;

    public AiCapExceededException(String userId, long retryAfterSeconds) {
        super("Daily AI interaction cap exceeded for user: " + userId);
        this.userId = userId;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getUserId()              { return userId; }
    public long getRetryAfterSeconds()     { return retryAfterSeconds; }
}
