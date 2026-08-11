package com.fieldservice.identity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis-backed implementation of {@link LoginAttemptTracker}.
 *
 * <p>Uses a Lua script for atomic INCR + conditional EXPIRE: the TTL is set only on
 * the first failure so the 15-minute window starts from the first attempt, not the last.
 *
 * <p>Active when a {@link StringRedisTemplate} bean is present (i.e., Redis autoconfiguration
 * is not excluded). Absent in the {@code test} profile — the in-memory fallback is used instead.
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
class RedisLoginAttemptTracker implements LoginAttemptTracker {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginAttemptTracker.class);

    private static final String KEY_PREFIX = "login:fail:";

    /** Atomic INCR + EXPIRE on first failure only (window never resets on each attempt). */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
            "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final long windowSeconds;

    RedisLoginAttemptTracker(StringRedisTemplate redis) {
        this.redis = redis;
        this.windowSeconds = 900L;
    }

    @Override
    public int getCount(String emailHash) {
        try {
            String val = redis.opsForValue().get(KEY_PREFIX + emailHash);
            return val == null ? 0 : Integer.parseInt(val);
        } catch (Exception e) {
            throw new LoginAttemptStoreException("Redis unavailable for lockout read", e);
        }
    }

    @Override
    public int recordFailure(String emailHash) {
        try {
            Long count = redis.execute(
                    INCR_WITH_TTL,
                    List.of(KEY_PREFIX + emailHash),
                    String.valueOf(windowSeconds));
            return count == null ? 1 : count.intValue();
        } catch (Exception e) {
            throw new LoginAttemptStoreException("Redis unavailable for failure recording", e);
        }
    }

    @Override
    public void resetCounter(String emailHash) {
        try {
            redis.delete(KEY_PREFIX + emailHash);
        } catch (Exception e) {
            log.warn("alert.login_counter_reset_failed emailHash={}... error={}",
                    emailHash.substring(0, Math.min(8, emailHash.length())), e.getMessage());
        }
    }
}
