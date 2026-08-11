package com.fieldservice.platform.exception;

/**
 * Thrown when a caller has exceeded their rate limit.
 *
 * <p>Maps to HTTP 429 Too Many Requests. The {@link #getRetryAfterSeconds()} value
 * is set as the {@code Retry-After} response header.
 */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super("Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
