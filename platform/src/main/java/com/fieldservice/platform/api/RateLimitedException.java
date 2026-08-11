package com.fieldservice.platform.api;

/**
 * Thrown when a caller exceeds a rate limit.
 * Maps to HTTP 429 with a {@code Retry-After} header in seconds.
 */
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, "Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
