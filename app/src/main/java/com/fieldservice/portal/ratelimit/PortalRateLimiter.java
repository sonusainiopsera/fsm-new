package com.fieldservice.portal.ratelimit;

import java.util.UUID;

/**
 * Portal-specific rate limiter keyed on account id.
 * Tighter than the authenticated baseline per WO-170 AC-7.
 */
public interface PortalRateLimiter {

    /**
     * Checks and records a request for the given account.
     *
     * @param accountId the customer account making the request
     * @throws com.fieldservice.platform.api.exception.RateLimitedException when the
     *         configured per-minute limit is exceeded
     */
    void checkAndRecord(UUID accountId);
}
