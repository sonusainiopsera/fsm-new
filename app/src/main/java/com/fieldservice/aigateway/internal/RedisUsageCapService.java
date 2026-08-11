package com.fieldservice.aigateway.internal;

import com.fieldservice.aigateway.api.AiCapExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Enforces a per-user per-day AI interaction cap using Redis INCR with midnight expiry.
 *
 * <p>Key pattern: {@code ai:cap:{userId}:{yyyyMMdd}} where date is UTC.
 * EXPIRE is set on every increment to the number of seconds remaining in the current UTC day,
 * ensuring counters roll over at midnight without double-charging or early reset.
 */
@Service
@ConditionalOnProperty(name = "ai.copilot.enabled", havingValue = "true")
class RedisUsageCapService {

    private static final Logger log = LoggerFactory.getLogger(RedisUsageCapService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;
    private final int dailyCapPerUser;

    RedisUsageCapService(StringRedisTemplate redis, AiGatewayProperties props) {
        this.redis = redis;
        this.dailyCapPerUser = props.copilot().dailyCapPerUser();
    }

    /**
     * Increments the counter for {@code userId} and throws {@link AiCapExceededException}
     * if the resulting count exceeds the configured daily limit.
     * The increment is NOT rolled back on cap breach — callers must not proceed after the throw.
     */
    void checkAndIncrement(String userId) {
        String key = buildKey(userId);
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // Redis returned null — treat as first use, allow the call
            return;
        }
        // Set or refresh expiry on every increment to handle the midnight boundary
        long secondsUntilMidnight = secondsUntilMidnightUtc();
        redis.expire(key, Duration.ofSeconds(secondsUntilMidnight));

        if (count > dailyCapPerUser) {
            log.info("ai_cap_exceeded userId={} count={} cap={}", userId, count, dailyCapPerUser);
            throw new AiCapExceededException(userId, secondsUntilMidnight);
        }
    }

    private String buildKey(String userId) {
        String today = LocalDate.now(ZoneOffset.UTC).format(DATE_FMT);
        return "ai:cap:" + userId + ":" + today;
    }

    static long secondsUntilMidnightUtc() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return ChronoUnit.SECONDS.between(now, midnight);
    }
}
