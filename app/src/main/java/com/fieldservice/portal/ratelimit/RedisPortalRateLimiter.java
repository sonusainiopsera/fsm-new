package com.fieldservice.portal.ratelimit;

import com.fieldservice.platform.exception.RateLimitedException;
import com.fieldservice.portal.config.PortalSubmissionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Redis-backed rate limiter for portal service-request submissions.
 *
 * <p>Active when a {@link StringRedisTemplate} bean is present (Redis autoconfiguration not
 * excluded). Uses a Lua script for atomic INCR + conditional EXPIRE: the TTL is set only on
 * the first request in a window so the window starts from the first request, not the most recent.
 *
 * <p>Key format: {@code portal:rate:{accountId}:{clientIp}}
 */
@Component("redisPortalRateLimiter")
@ConditionalOnBean(StringRedisTemplate.class)
class RedisPortalRateLimiter implements PortalRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisPortalRateLimiter.class);

    private static final String KEY_PREFIX = "portal:rate:";

    /** Atomic INCR + EXPIRE on the first request in each window. */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
            "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final PortalSubmissionProperties props;

    RedisPortalRateLimiter(StringRedisTemplate redis, PortalSubmissionProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    public void checkAndRecord(UUID accountId, String clientIp) {
        String key = KEY_PREFIX + accountId + ":" + clientIp;
        long windowSeconds = props.getRateLimitWindowSeconds();
        int limit = props.getRateLimitMax();

        try {
            Long count = redis.execute(
                    INCR_WITH_TTL,
                    List.of(key),
                    String.valueOf(windowSeconds));

            if (count != null && count > limit) {
                throw new RateLimitedException(
                        "Portal rate limit exceeded for account " + accountId,
                        windowSeconds);
            }
        } catch (RateLimitedException ex) {
            throw ex;
        } catch (Exception ex) {
            // Redis unavailable: fail open with a warning (availability > strict rate limiting)
            log.warn("portal.rate_limit_check_failed: Redis unavailable, allowing request; " +
                     "accountId={} error={}", accountId, ex.getMessage());
        }
    }
}
