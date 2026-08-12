package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.TravelCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed implementation of {@link TravelCacheGateway}.
 *
 * <p>Key format: {@code travel:{originHash}:{destHash}} (see {@link CoordinateRounder}).
 * Coordinates are rounded before hashing to satisfy BR-23 data minimisation and to
 * maximise cache hit ratio. TTL is 300 seconds (configurable via {@code geo.travel.cache.ttl}).
 *
 * <p>All Redis errors are caught and logged at WARN level — a Redis outage degrades
 * to cache-miss behaviour and never blocks the recommendation path.
 */
class RedisTravelCacheGateway implements TravelCacheGateway {

    private static final Logger log = LoggerFactory.getLogger(RedisTravelCacheGateway.class);

    private final RedisTemplate<String, Double> redisTemplate;
    private final Duration ttl;

    RedisTravelCacheGateway(RedisTemplate<String, Double> redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    @Override
    public Optional<Double> get(TravelCoordinate origin, TravelCoordinate destination) {
        String key = CoordinateRounder.cacheKey(origin, destination);
        try {
            Double value = redisTemplate.opsForValue().get(key);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("geo.cache.get.failed key={} error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(TravelCoordinate origin, TravelCoordinate destination, double estimatedMinutes) {
        String key = CoordinateRounder.cacheKey(origin, destination);
        try {
            redisTemplate.opsForValue().set(key, estimatedMinutes, ttl);
        } catch (Exception e) {
            log.warn("geo.cache.put.failed key={} error={}", key, e.getMessage());
        }
    }
}
