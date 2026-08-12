package com.fieldservice.geo.internal;

import com.fieldservice.geo.api.Coordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed cache for individual origin→destination travel-time estimates.
 *
 * <h3>Key schema</h3>
 * <pre>
 *   travel:{originHash}:{destHash}
 * </pre>
 * where {@code originHash} and {@code destHash} are derived from coordinates rounded
 * to {@link TravelProviderProperties.Cache#coordinatePrecisionDecimalPlaces()} decimal
 * places — currently 4 dp, giving ~11 m precision. Rounding reduces cardinality (raising
 * cache hit ratio) and minimises stored precision in line with BR-23 data-minimisation.
 *
 * <h3>Resilience</h3>
 * Any Redis error is caught and logged at WARN. The caller treats a cache miss as if
 * Redis were unavailable and proceeds to the provider. A single WARN is emitted per
 * Redis failure; no exception escapes to the scoring path.
 */
class TravelCacheGateway {

    private static final Logger log = LoggerFactory.getLogger(TravelCacheGateway.class);
    private static final String KEY_PREFIX = "travel:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final int precisionDp;

    TravelCacheGateway(StringRedisTemplate redisTemplate,
                       TravelProviderProperties props) {
        this.redisTemplate = redisTemplate;
        this.ttl           = Duration.ofSeconds(props.cache().ttlSeconds());
        this.precisionDp   = props.cache().coordinatePrecisionDecimalPlaces();
    }

    /**
     * Returns the cached estimate in minutes, or empty when the cache is cold or unavailable.
     */
    Optional<Integer> get(Coordinates origin, Coordinates destination) {
        String key = buildKey(origin, destination);
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) return Optional.empty();
            return Optional.of(Integer.parseInt(value));
        } catch (Exception e) {
            log.warn("geo.travel.cache_read_failed key={} reason={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores an estimate in the cache with the configured TTL.
     * Failures are silently logged; the absence of a cache write never fails a request.
     */
    void put(Coordinates origin, Coordinates destination, int estimatedMinutes) {
        String key = buildKey(origin, destination);
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(estimatedMinutes), ttl);
        } catch (Exception e) {
            log.warn("geo.travel.cache_write_failed key={} reason={}", key, e.getMessage());
        }
    }

    /**
     * Builds the cache key from rounded coordinates.
     * Package-private to allow unit testing without a Redis instance.
     */
    String buildKey(Coordinates origin, Coordinates destination) {
        return KEY_PREFIX + coordHash(origin) + ":" + coordHash(destination);
    }

    /**
     * Produces a deterministic hash string from coordinates rounded to the configured precision.
     * Format: "{lat}_{lon}" with rounded values using HALF_UP.
     */
    String coordHash(Coordinates c) {
        BigDecimal lat = BigDecimal.valueOf(c.latitude()).setScale(precisionDp, RoundingMode.HALF_UP);
        BigDecimal lon = BigDecimal.valueOf(c.longitude()).setScale(precisionDp, RoundingMode.HALF_UP);
        return lat.toPlainString() + "_" + lon.toPlainString();
    }
}
