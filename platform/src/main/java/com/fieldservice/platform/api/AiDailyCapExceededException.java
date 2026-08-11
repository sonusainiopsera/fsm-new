package com.fieldservice.platform.api;

/**
 * Thrown when a user has exceeded their per-day AI interaction cap.
 * Maps to HTTP 429 with code {@link ErrorCode#AI_DAILY_LIMIT_REACHED} and a
 * {@code Retry-After} header indicating seconds until the counter rolls over at midnight.
 */
public class AiDailyCapExceededException extends ApiException {

    private final long retryAfterSeconds;

    public AiDailyCapExceededException(long retryAfterSeconds) {
        super(ErrorCode.AI_DAILY_LIMIT_REACHED,
                "Daily AI interaction limit reached. Retry after " + retryAfterSeconds + " seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
