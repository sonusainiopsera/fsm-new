package com.fieldservice.aigateway.internal;

import com.fieldservice.platform.api.AiDailyCapExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Per-user, per-day AI interaction cap enforced via Redis INCR/EXPIRE.
 *
 * <p>Key pattern: {@code ai:cap:{userId}:{yyyyMMdd}}
 * <p>The key expires at midnight UTC so the counter rolls over automatically.
 * <p>All operations are atomic at the INCR step; the EXPIREAT is idempotent.
 */
class RedisUsageCapService {

    private static final Logger log = LoggerFactory.getLogger(RedisUsageCapService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redis;
    private final int dailyCapPerUser;
    private final Clock clock;

    RedisUsageCapService(StringRedisTemplate redis, int dailyCapPerUser, Clock clock) {
        this.redis = redis;
        this.dailyCapPerUser = dailyCapPerUser;
        this.clock = clock;
    }

    /**
     * Atomically increments the counter for {@code userId} and throws
     * {@link AiDailyCapExceededException} if the new count exceeds the cap.
     *
     * <p>The check-then-use design means callers who are already over the cap
     * will still have the counter incremented; this is acceptable as the cap
     * is a soft-cost limit, not a hard rate limit.
     */
    void checkAndIncrement(UUID userId) {
        String key = buildKey(userId);
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // Redis unavailable — fail closed
            log.error("Redis cap check failed: null count for key={}", key);
            throw new com.fieldservice.platform.api.AiUnavailableException(
                    "AI assistance is temporarily unavailable.");
        }
        if (count == 1L) {
            // First increment today — set expiry at end of UTC day
            redis.expireAt(key, endOfDayInstant());
        }
        if (count > dailyCapPerUser) {
            long retryAfter = secondsUntilMidnight();
            log.info("AI daily cap exceeded: userId={}", userId);
            throw new AiDailyCapExceededException(retryAfter);
        }
    }

    String buildKey(UUID userId) {
        LocalDate today = LocalDate.now(clock);
        return "ai:cap:" + userId + ":" + today.format(DATE_FMT);
    }

    private java.time.Instant endOfDayInstant() {
        LocalDate today = LocalDate.now(clock);
        return LocalDateTime.of(today.plusDays(1), LocalTime.MIDNIGHT)
                .toInstant(ZoneOffset.UTC);
    }

    private long secondsUntilMidnight() {
        java.time.Instant now = clock.instant();
        java.time.Instant midnight = endOfDayInstant();
        return Duration.between(now, midnight).getSeconds();
    }
}
