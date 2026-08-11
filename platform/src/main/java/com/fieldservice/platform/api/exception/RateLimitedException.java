package com.fieldservice.platform.api.exception;

/** Thrown when the caller exceeds a rate limit. Maps to HTTP 429 with a Retry-After header. */
public class RateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
