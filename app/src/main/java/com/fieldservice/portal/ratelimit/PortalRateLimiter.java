package com.fieldservice.portal.ratelimit;

import java.util.UUID;

/**
 * Rate limiter for portal service-request submissions.
 *
 * <p>Keyed on account ID + client IP so a single compromised account cannot
 * flood from multiple IPs and a shared IP (e.g. a corporate NAT) cannot be
 * weaponised to block a legitimate account.
 *
 * <p>Implementations throw {@link com.fieldservice.platform.exception.RateLimitedException}
 * when the caller has exceeded their quota for the current window.
 */
public interface PortalRateLimiter {

    /**
     * Checks and records a submission attempt for the given account and IP.
     *
     * @param accountId the resolved customer account UUID
     * @param clientIp  the client's remote IP address (may be {@code "unknown"})
     * @throws com.fieldservice.platform.exception.RateLimitedException when the limit is exceeded
     */
    void checkAndRecord(UUID accountId, String clientIp);
}
