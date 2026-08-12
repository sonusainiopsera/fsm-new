package com.fieldservice.portal.ratelimit;

import com.fieldservice.platform.api.exception.RateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory portal rate limiter using a sliding window counter per account.
 * Suitable for single-instance deployments and integration tests.
 *
 * <p>For multi-replica deployments, replace with a Redis-backed implementation
 * using INCR + EXPIRE on a key scoped to {@code portal:rl:{accountId}:{epochMinute}}.
 */
@Component
public class InMemoryPortalRateLimiter implements PortalRateLimiter {

    /** Default: 5 requests per minute per account — tighter than the authenticated baseline. */
    private final int requestsPerMinute;

    private final ConcurrentHashMap<String, WindowEntry> windows = new ConcurrentHashMap<>();

    public InMemoryPortalRateLimiter(
            @Value("${app.portal.rate-limit.requests-per-minute:5}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    public void checkAndRecord(UUID accountId) {
        long currentMinute = System.currentTimeMillis() / 60_000;
        String key = accountId + ":" + currentMinute;

        WindowEntry entry = windows.computeIfAbsent(key, k -> new WindowEntry());
        int count = entry.counter.incrementAndGet();

        // Evict stale entries from previous minutes to avoid unbounded growth
        windows.entrySet().removeIf(e -> {
            String[] parts = e.getKey().split(":");
            long minute = Long.parseLong(parts[parts.length - 1]);
            return minute < currentMinute - 1;
        });

        if (count > requestsPerMinute) {
            long retryAfterSeconds = 60 - (System.currentTimeMillis() / 1000 % 60);
            throw new RateLimitedException(retryAfterSeconds);
        }
    }

    private static class WindowEntry {
        final AtomicInteger counter = new AtomicInteger(0);
    }
}
