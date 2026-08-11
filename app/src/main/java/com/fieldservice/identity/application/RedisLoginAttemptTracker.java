package com.fieldservice.identity.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed implementation of {@link LoginAttemptTracker}.
 *
 * <p>Uses atomic {@code INCR} to count failures and {@code EXPIRE} with a 900-second
 * (15-minute) TTL set on the first failure only, so the observation window is fixed
 * and cannot be extended by repeated attempts. {@code DEL} on successful login resets
 * the counter immediately.
 *
 * <p>Key format: {@code auth:lockout:{sha256hex(email)}} — plaintext email is never
 * stored in Redis.
 *
 * <p>Fail-closed: any Redis error propagates to the caller so the service layer can
 * return 503 instead of silently permitting unlimited attempts.
 */
@Service
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisLoginAttemptTracker implements LoginAttemptTracker {

    private static final String KEY_PREFIX    = "auth:lockout:";
    private static final int    TTL_SECONDS   = 900;
    private static final int    MAX_FAILURES  = 5;
    private static final Duration LOCKOUT_TTL = Duration.ofSeconds(TTL_SECONDS);

    private final StringRedisTemplate redis;
    private final int maxFailures;

    public RedisLoginAttemptTracker(StringRedisTemplate redis) {
        this(redis, MAX_FAILURES);
    }

    RedisLoginAttemptTracker(StringRedisTemplate redis, int maxFailures) {
        this.redis       = redis;
        this.maxFailures = maxFailures;
    }

    @Override
    public int recordFailure(String emailHash) {
        String key = KEY_PREFIX + emailHash;
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("Redis INCR returned null for key " + key);
        }
        if (count == 1L) {
            // First failure: set the TTL so the window expires after 900 seconds
            redis.expire(key, LOCKOUT_TTL);
        }
        return count.intValue();
    }

    @Override
    public void recordSuccess(String emailHash) {
        redis.delete(KEY_PREFIX + emailHash);
    }

    @Override
    public boolean isLocked(String emailHash) {
        String value = redis.opsForValue().get(KEY_PREFIX + emailHash);
        if (value == null) {
            return false;
        }
        try {
            return Integer.parseInt(value) >= maxFailures;
        } catch (NumberFormatException e) {
            // Corrupt Redis entry — fail closed
            return true;
        }
    }
}
