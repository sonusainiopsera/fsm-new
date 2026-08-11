package com.fieldservice.aigateway.internal;

import com.fieldservice.platform.api.exception.AiCapExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Redis-backed daily cap using INCR + EXPIRE at end-of-day.
 * Key pattern: {@code ai:cap:{userId}:{yyyyMMdd}}.
 */
class RedisUsageCapService implements UsageCapService {

    private static final Logger log = LoggerFactory.getLogger(RedisUsageCapService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;

    RedisUsageCapService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void checkAndIncrement(String userId, int dailyLimit) {
        String key = buildKey(userId);
        Long count = redis.opsForValue().increment(key);

        if (count == null) {
            log.warn("ai_cap_check_failed user_id={} reason=redis_returned_null", userId);
            return; // fail open if Redis is unreachable
        }

        if (count == 1L) {
            // First use today — set expiry to end of UTC day
            redis.expire(key, ttlUntilMidnightUtc());
        }

        if (count > dailyLimit) {
            long retryAfter = ttlUntilMidnightUtc().getSeconds();
            log.info("ai_daily_cap_exceeded user_id={} count={} limit={}", userId, count, dailyLimit);
            // Decrement to prevent indefinite accumulation beyond limit+1
            redis.opsForValue().decrement(key);
            throw new AiCapExceededException(userId, retryAfter);
        }
    }

    static String buildKey(String userId) {
        String date = LocalDate.now(ZoneOffset.UTC).format(DATE_FMT);
        return "ai:cap:" + userId + ":" + date;
    }

    private static Duration ttlUntilMidnightUtc() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return Duration.between(now, midnight);
    }
}
