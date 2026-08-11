package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Translates replica or Redis failures into degraded {@link KpiProjection} responses.
 *
 * <p>Contract (policy A10 — graceful degradation):
 * <ul>
 *   <li>A degraded response always carries the last-committed projection value (or null
 *       if no projection exists yet) plus {@code degraded=true} and a non-blank reason.</li>
 *   <li>A degraded response is never indistinguishable from a fresh one — the degraded
 *       flag must propagate to every caller.</li>
 *   <li>This component never throws to the caller. Exceptions are caught, structured-logged,
 *       and translated into degraded responses.</li>
 * </ul>
 */
@Component
class DegradationPolicy {

    private static final Logger log = LoggerFactory.getLogger(DegradationPolicy.class);

    static final String REASON_REPLICA_UNAVAILABLE = "REPLICA_UNAVAILABLE";
    static final String REASON_CACHE_UNAVAILABLE   = "CACHE_UNAVAILABLE";
    static final String REASON_NO_DATA             = "NO_DATA";
    static final String REASON_REPLICA_LAG         = "REPLICA_LAG_EXCEEDED";

    private final Clock clock;

    DegradationPolicy(Clock clock) {
        this.clock = clock;
    }

    /**
     * Wraps a replica exception, returning a degraded projection from the last-known value
     * stored in {@code fallback} (may be {@code null} if no prior projection exists).
     */
    KpiProjection onReplicaFailure(
            String metricKey, String segmentKey, String windowKey,
            KpiProjectionEntity fallback, Exception cause) {

        log.error("analytics.degraded.replica_failure: metricKey={} segmentKey={} windowKey={} — {}",
                metricKey, segmentKey, windowKey, cause.getMessage());

        return toProjection(metricKey, segmentKey, windowKey, fallback, REASON_REPLICA_UNAVAILABLE);
    }

    /**
     * Wraps a Redis cache exception, returning a degraded projection from the DB fallback.
     */
    KpiProjection onCacheFailure(
            String metricKey, String segmentKey, String windowKey,
            KpiProjectionEntity fallback, Exception cause) {

        log.warn("analytics.degraded.cache_failure: metricKey={} — {}", metricKey, cause.getMessage());

        return toProjection(metricKey, segmentKey, windowKey, fallback, REASON_CACHE_UNAVAILABLE);
    }

    /**
     * Returns a degraded placeholder when no projection has ever been computed.
     */
    KpiProjection onNoData(String metricKey, String segmentKey, String windowKey) {
        Instant now = clock.instant();
        return KpiProjection.degraded(metricKey, segmentKey, windowKey, REASON_NO_DATA, 0L, now);
    }

    private KpiProjection toProjection(
            String metricKey, String segmentKey, String windowKey,
            KpiProjectionEntity fallback, String reason) {

        Instant now = clock.instant();
        if (fallback == null) {
            return KpiProjection.degraded(metricKey, segmentKey, windowKey, reason, 0L, now);
        }

        long staleness = now.getEpochSecond() - fallback.getDataAsOf().getEpochSecond();
        return new KpiProjection(
                fallback.getMetricKey(), fallback.getSegmentKey(), fallback.getWindowKey(),
                fallback.getValue(), fallback.getNumerator(), fallback.getDenominator(),
                fallback.getSampleCount(), fallback.getMaturity(), fallback.getDataAsOf(),
                fallback.getProjectionVersion(), true, reason, staleness);
    }
}
