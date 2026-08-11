package com.fieldservice.analytics;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable read-model projection DTO returned by the analytics public interface.
 *
 * <p>Every projection carries {@link #dataAsOf()} (the instant the value was last computed)
 * and {@link #degraded()} (true when the replica or cache was unavailable and the value
 * may not reflect the latest committed data). Callers must never treat a degraded projection
 * as authoritative for decisions, but must still surface it with the degraded indicator
 * rather than surfacing an error or a blank tile.
 *
 * <p>{@link #stalenessSeconds()} is derived at serialization time by the calling layer
 * and is NOT stored in the database — it tells the consumer how old this snapshot is
 * relative to the current wall-clock time.
 *
 * <p>Data classification: Internal (BR-23). Projections contain aggregate counts only —
 * no PII, no customer names, no individual work-order bodies.
 */
public record KpiProjection(
        String metricKey,
        String segmentKey,
        String windowKey,
        @Nullable BigDecimal value,
        @Nullable BigDecimal numerator,
        @Nullable BigDecimal denominator,
        int sampleCount,
        String maturity,
        Instant dataAsOf,
        long projectionVersion,
        boolean degraded,
        @Nullable String degradedReason,
        long stalenessSeconds
) {
    /** Convenience factory for a degraded placeholder (no computable value). */
    public static KpiProjection degraded(
            String metricKey, String segmentKey, String windowKey,
            String reason, long projectionVersion, Instant now) {
        return new KpiProjection(
                metricKey, segmentKey, windowKey,
                null, null, null, 0, "CURRENT",
                now, projectionVersion, true, reason, 0L);
    }
}
