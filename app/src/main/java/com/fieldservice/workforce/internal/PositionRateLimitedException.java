package com.fieldservice.workforce.internal;

public class PositionRateLimitedException extends RuntimeException {
    private static final int RETRY_AFTER_SECONDS = 30;

    public PositionRateLimitedException() {
        super("Position report rate limit exceeded; retry after " + RETRY_AFTER_SECONDS + " seconds");
    }

    public int getRetryAfterSeconds() { return RETRY_AFTER_SECONDS; }
}
