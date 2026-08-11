package com.fieldservice.portal.ratelimit;

import com.fieldservice.platform.exception.RateLimitedException;
import com.fieldservice.portal.config.PortalSubmissionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory fallback rate limiter for portal submissions.
 *
 * <p>Active when no {@link RedisPortalRateLimiter} is registered (i.e., in the test
 * profile where Redis autoconfiguration is excluded). Not suitable for multi-replica
 * deployments — use only in single-process environments or tests.
 *
 * <p>Window entries expire when first checked after their TTL. Entries from a prior
 * window do not accumulate — the counter resets when the window elapses.
 */
@Component
@ConditionalOnMissingBean(name = "redisPortalRateLimiter")
class InMemoryPortalRateLimiter implements PortalRateLimiter {

    private final PortalSubmissionProperties props;

    private final ConcurrentHashMap<String, WindowEntry> counters = new ConcurrentHashMap<>();

    InMemoryPortalRateLimiter(PortalSubmissionProperties props) {
        this.props = props;
    }

    @Override
    public void checkAndRecord(UUID accountId, String clientIp) {
        String key = accountId + ":" + clientIp;
        long windowSeconds = props.getRateLimitWindowSeconds();
        int limit = props.getRateLimitMax();
        Instant windowStart = Instant.now();

        WindowEntry entry = counters.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(windowStart, windowSeconds)) {
                return new WindowEntry(windowStart);
            }
            return existing;
        });

        int count = entry.counter.incrementAndGet();
        if (count > limit) {
            throw new RateLimitedException("Portal rate limit exceeded", windowSeconds);
        }
    }

    private static final class WindowEntry {
        final Instant windowStart;
        final AtomicInteger counter = new AtomicInteger(0);

        WindowEntry(Instant windowStart) {
            this.windowStart = windowStart;
        }

        boolean isExpired(Instant now, long windowSeconds) {
            return now.getEpochSecond() - windowStart.getEpochSecond() >= windowSeconds;
        }
    }
}
