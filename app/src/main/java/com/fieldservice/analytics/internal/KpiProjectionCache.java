package com.fieldservice.analytics.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.analytics.KpiProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis projection cache with version-qualified keys and a 30-second TTL.
 *
 * <p>Key pattern: {@code kpi:{metric}:{segment}:{window}:v{projectionVersion}}
 * <br>Pointer key:  {@code kpi:{metric}:{segment}:{window}:ptr} → projectionVersion (TTL 60 s)
 *
 * <p>The pointer key enables degraded DB reads to find the latest cached version
 * even when the projection table is unreachable.
 *
 * <p>Redis failures are caught and logged at WARN level; the caller receives an
 * empty Optional and falls back to the DB path or the degradation policy.
 * This class never propagates a Redis exception to the caller.
 */
@Component
class KpiProjectionCache {

    private static final Logger log = LoggerFactory.getLogger(KpiProjectionCache.class);

    static final Duration VALUE_TTL   = Duration.ofSeconds(30);
    static final Duration POINTER_TTL = Duration.ofSeconds(60);

    @Nullable
    private final StringRedisTemplate redis;
    private final ObjectMapper        objectMapper;

    KpiProjectionCache(@Autowired(required = false) StringRedisTemplate redis,
                       ObjectMapper objectMapper) {
        this.redis        = redis;
        this.objectMapper = objectMapper;
    }

    Optional<KpiProjection> get(String metricKey, String segmentKey, String windowKey, long version) {
        if (redis == null) return Optional.empty();
        String key = valueKey(metricKey, segmentKey, windowKey, version);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, KpiProjection.class));
        } catch (JsonProcessingException e) {
            log.warn("analytics_cache_deserialize_error key={} error={}", key, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("analytics_cache_read_error key={} error={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    void put(KpiProjection projection) {
        if (redis == null) return;
        String valueKey   = valueKey(projection.metricKey(), projection.segmentKey(),
                                     projection.windowKey(), projection.projectionVersion());
        String pointerKey = pointerKey(projection.metricKey(), projection.segmentKey(), projection.windowKey());
        try {
            String json = objectMapper.writeValueAsString(projection);
            redis.opsForValue().set(valueKey,   json, VALUE_TTL);
            redis.opsForValue().set(pointerKey, String.valueOf(projection.projectionVersion()), POINTER_TTL);
        } catch (JsonProcessingException e) {
            log.warn("analytics_cache_serialize_error metric={} error={}", projection.metricKey(), e.getMessage());
        } catch (Exception e) {
            log.warn("analytics_cache_write_error metric={} error={}", projection.metricKey(), e.getMessage());
        }
    }

    /**
     * Attempts to read the last-known projection from cache when the DB is unavailable.
     * Returns empty if neither the pointer key nor the value key is present.
     */
    Optional<KpiProjection> getLastKnown(String metricKey, String segmentKey, String windowKey) {
        if (redis == null) return Optional.empty();
        try {
            String versionStr = redis.opsForValue().get(pointerKey(metricKey, segmentKey, windowKey));
            if (versionStr == null) {
                return Optional.empty();
            }
            long version = Long.parseLong(versionStr);
            return get(metricKey, segmentKey, windowKey, version);
        } catch (Exception e) {
            log.warn("analytics_cache_last_known_error metric={} error={}", metricKey, e.getMessage());
            return Optional.empty();
        }
    }

    private static String valueKey(String metricKey, String segmentKey, String windowKey, long version) {
        return "kpi:" + metricKey + ":" + segmentKey + ":" + windowKey + ":v" + version;
    }

    private static String pointerKey(String metricKey, String segmentKey, String windowKey) {
        return "kpi:" + metricKey + ":" + segmentKey + ":" + windowKey + ":ptr";
    }
}
