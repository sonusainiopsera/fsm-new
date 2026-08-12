package com.fieldservice.inventory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed availability cache (WO-151 AC-5).
 *
 * <p>Key format: {@code availability:{stableHashKey}}
 * TTL is configurable, capped at 60 seconds to satisfy BR-15 freshness (default: 45s).
 *
 * <p>All Redis errors are caught, logged, and return empty — the caller falls through
 * to a direct DB query and increments the degraded counter.
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
class RedisAvailabilityCacheGateway implements AvailabilityCacheGateway {

    private static final Logger log = LoggerFactory.getLogger(RedisAvailabilityCacheGateway.class);
    private static final String KEY_PREFIX = "availability:";
    private static final long MAX_TTL_SECONDS = 60L;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    RedisAvailabilityCacheGateway(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${inventory.availability.cache.ttl-seconds:45}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        long capped = Math.min(ttlSeconds, MAX_TTL_SECONDS);
        this.ttl = Duration.ofSeconds(capped);
    }

    @Override
    public Optional<PartsAvailabilityResult> get(String cacheKey) {
        String key = KEY_PREFIX + cacheKey;
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, PartsAvailabilityResult.class));
        } catch (Exception e) {
            log.warn("inventory.availability.cache.get.failed key={} error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String cacheKey, PartsAvailabilityResult result) {
        String key = KEY_PREFIX + cacheKey;
        try {
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.warn("inventory.availability.cache.put.failed key={} error={}", key, e.getMessage());
        }
    }
}
