package com.fieldservice.analytics.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.analytics.KpiProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis projection cache with a 30-second TTL (WO-161).
 *
 * <p>Key pattern: {@code kpi:{metricKey}:{segmentKey}:{windowKey}:v{projectionVersion}}
 * The projection_version in the key means a cached value written by an older projection
 * is automatically a miss — it will not match the current version and falls through to
 * the DB. This satisfies the AC requirement that version-mismatched cache entries are
 * treated as misses, not deserialized blindly.
 *
 * <p>Active only when a {@link StringRedisTemplate} bean is present. When Redis is
 * unavailable the {@link DegradationPolicy} handles the failure path.
 */
@Component
@ConditionalOnBean(StringRedisTemplate.class)
class AnalyticsRedisCache {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRedisCache.class);

    static final Duration TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    AnalyticsRedisCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Looks up a projection from the cache.
     *
     * <p>Uses the current {@code projectionVersion} as part of the key so stale entries
     * (written by an older version) are automatically treated as misses.
     */
    @Nullable
    KpiProjection get(String metricKey, String segmentKey, String windowKey, long projectionVersion) {
        String key = cacheKey(metricKey, segmentKey, windowKey, projectionVersion);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, KpiProjection.class);
        } catch (Exception ex) {
            log.warn("analytics.cache.read_error: key={} — {}", key, ex.getMessage());
            throw new AnalyticsCacheException("Redis read failed for key=" + key, ex);
        }
    }

    /** Writes a projection to the cache with 30-second TTL. */
    void put(KpiProjection projection) {
        String key = cacheKey(projection.metricKey(), projection.segmentKey(),
                projection.windowKey(), projection.projectionVersion());
        try {
            String json = objectMapper.writeValueAsString(projection);
            redis.opsForValue().set(key, json, TTL);
        } catch (JsonProcessingException ex) {
            log.warn("analytics.cache.serialize_error: key={} — {}", key, ex.getMessage());
        } catch (Exception ex) {
            log.warn("analytics.cache.write_error: key={} — {}", key, ex.getMessage());
        }
    }

    /** Evicts all versioned keys for a metric/segment/window (called before projection write). */
    void evict(String metricKey, String segmentKey, String windowKey) {
        // Pattern scan: kpi:{metric}:{segment}:{window}:v*
        String pattern = "kpi:" + metricKey + ":" + segmentKey + ":" + windowKey + ":v*";
        try {
            var keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception ex) {
            log.warn("analytics.cache.evict_error: pattern={} — {}", pattern, ex.getMessage());
        }
    }

    static String cacheKey(String metricKey, String segmentKey, String windowKey, long version) {
        return "kpi:" + metricKey + ":" + segmentKey + ":" + windowKey + ":v" + version;
    }

    static class AnalyticsCacheException extends RuntimeException {
        AnalyticsCacheException(String msg, Throwable cause) { super(msg, cause); }
    }
}
