package com.fieldservice.workforce.internal;

import com.fieldservice.workforce.api.TechnicianPositionCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed implementation of {@link TechnicianPositionCachePort}.
 *
 * Key schema: {@code technician:{id}:position}
 * Value: comma-separated {@code lat,lon,accuracyMetres,capturedAtEpochMs}
 * TTL: 60 seconds
 *
 * Any Redis failure is caught and logged at WARN; no exception escapes to callers.
 */
@Component
class TechnicianPositionCacheGateway implements TechnicianPositionCachePort {

    private static final Logger log = LoggerFactory.getLogger(TechnicianPositionCacheGateway.class);
    private static final Duration POSITION_TTL      = Duration.ofSeconds(60);
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    TechnicianPositionCacheGateway(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<CachedPosition> findCachedPosition(UUID technicianId) {
        String key = positionKey(technicianId);
        try {
            String value = redis.opsForValue().get(key);
            if (value == null) return Optional.empty();
            String[] parts = value.split(",", 4);
            if (parts.length < 4) return Optional.empty();
            double  lat      = Double.parseDouble(parts[0]);
            double  lon      = Double.parseDouble(parts[1]);
            int     accuracy = Integer.parseInt(parts[2]);
            Instant captured = Instant.ofEpochMilli(Long.parseLong(parts[3]));
            return Optional.of(new CachedPosition(lat, lon, accuracy, captured));
        } catch (Exception e) {
            log.warn("position.cache.read_failed technicianId={} reason={}", technicianId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void writeCachedPosition(UUID technicianId, CachedPosition position) {
        String key   = positionKey(technicianId);
        String value = position.latitude() + "," + position.longitude() + ","
                + position.accuracyMetres() + "," + position.capturedAt().toEpochMilli();
        try {
            redis.opsForValue().set(key, value, POSITION_TTL);
        } catch (Exception e) {
            log.warn("position.cache.write_failed technicianId={} reason={}", technicianId, e.getMessage());
        }
    }

    /**
     * Attempts to acquire a rate-limit token for the given technician.
     * Returns {@code true} if the request should proceed (token acquired),
     * {@code false} if the rate limit window is already active.
     *
     * Uses atomic SET NX (set if not exists) with 30-second TTL.
     */
    boolean tryAcquireRateLimit(UUID technicianId) {
        String key = rateLimitKey(technicianId);
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", RATE_LIMIT_WINDOW);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("position.ratelimit.check_failed technicianId={} reason={}", technicianId, e.getMessage());
            // On cache failure, allow the request through (defence in depth — client throttles too)
            return true;
        }
    }

    private static String positionKey(UUID technicianId) {
        return "technician:" + technicianId + ":position";
    }

    private static String rateLimitKey(UUID technicianId) {
        return "position:ratelimit:" + technicianId;
    }
}
