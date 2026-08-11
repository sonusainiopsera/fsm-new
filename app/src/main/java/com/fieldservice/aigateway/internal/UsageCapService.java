package com.fieldservice.aigateway.internal;

/**
 * Per-user daily interaction cap. Callers invoke {@link #checkAndIncrement} before
 * dispatching to the provider; a cap breach throws {@link com.fieldservice.platform.api.exception.AiCapExceededException}.
 */
interface UsageCapService {

    /**
     * Atomically checks whether the user has remaining quota and increments the counter.
     *
     * @param userId the authenticated user identifier
     * @param dailyLimit maximum interactions per day
     * @throws com.fieldservice.platform.api.exception.AiCapExceededException if the limit is already reached
     */
    void checkAndIncrement(String userId, int dailyLimit);
}
