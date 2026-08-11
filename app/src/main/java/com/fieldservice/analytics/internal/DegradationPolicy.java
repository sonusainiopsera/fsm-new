package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Translates infrastructure failures into degraded {@link KpiProjection} responses.
 *
 * <p>Policy:
 * <ul>
 *   <li>DB failure: attempt a Redis last-known lookup; return it with
 *       {@code degraded = true} and the original {@code dataAsOf}.</li>
 *   <li>Redis failure on write: log at WARN; silently ignored (DB is authoritative).</li>
 *   <li>Both fail: return a zero-value projection with {@code degraded = true} and
 *       {@code dataAsOf = epoch} to signal no data. Never throw to the caller.</li>
 * </ul>
 *
 * <p>This component never surfaces exceptions beyond this class boundary (policy A10).
 */
@Component
class DegradationPolicy {

    private static final Logger log = LoggerFactory.getLogger(DegradationPolicy.class);

    private final KpiProjectionCache cache;
    private final Clock              clock;

    DegradationPolicy(KpiProjectionCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Called when the projection table read fails for a query.
     *
     * <p>Attempts to return the last-known cached value with a degraded flag.
     * If that also fails, returns a synthetic degraded projection with no value data.
     */
    Optional<KpiProjection> handleQueryFailure(String metricKey, String segmentKey,
                                                String windowKey, Exception cause) {
        log.warn("analytics_query_degraded metric_key={} segment_key={} window_key={} reason={}",
                metricKey, segmentKey, windowKey, cause.getMessage());

        try {
            Optional<KpiProjection> lastKnown = cache.getLastKnown(metricKey, segmentKey, windowKey);
            if (lastKnown.isPresent()) {
                KpiProjection p = lastKnown.get();
                long staleness = computeStaleness(p.dataAsOf());
                return Optional.of(asDegraded(p, staleness));
            }
        } catch (Exception cacheEx) {
            log.warn("analytics_cache_fallback_failed metric_key={} reason={}",
                    metricKey, cacheEx.getMessage());
        }

        return Optional.of(emptyDegraded(metricKey, segmentKey, windowKey));
    }

    private KpiProjection asDegraded(KpiProjection original, long stalenessSeconds) {
        return new KpiProjection(
                original.id(), original.metricKey(), original.segmentKey(), original.windowKey(),
                original.value(), original.numerator(), original.denominator(),
                original.sampleCount(), original.maturity(),
                original.dataAsOf(), original.projectionVersion(),
                true, stalenessSeconds
        );
    }

    private KpiProjection emptyDegraded(String metricKey, String segmentKey, String windowKey) {
        return new KpiProjection(
                null, metricKey, segmentKey, windowKey,
                null, null, null, null, "PROVISIONAL",
                Instant.EPOCH, 0L,
                true, Long.MAX_VALUE
        );
    }

    private long computeStaleness(@Nullable Instant dataAsOf) {
        if (dataAsOf == null || dataAsOf.equals(Instant.EPOCH)) {
            return Long.MAX_VALUE;
        }
        long seconds = clock.instant().getEpochSecond() - dataAsOf.getEpochSecond();
        return Math.max(0L, seconds);
    }
}
