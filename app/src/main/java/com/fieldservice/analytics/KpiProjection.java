package com.fieldservice.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable KPI projection DTO — the public face of the analytics read model.
 *
 * <p>{@code degraded} is true whenever the projection was computed under a partial
 * failure (replica unavailable, Redis outage). Callers must surface this flag rather
 * than treating the value as fully fresh.
 *
 * <p>{@code stalenessSeconds} is computed at read time as {@code now - dataAsOf}.
 * It is never negative; a freshly-refreshed projection has staleness near zero.
 */
public record KpiProjection(
        UUID       id,
        String     metricKey,
        String     segmentKey,
        String     windowKey,
        BigDecimal value,
        BigDecimal numerator,
        BigDecimal denominator,
        Integer    sampleCount,
        String     maturity,
        Instant    dataAsOf,
        long       projectionVersion,
        boolean    degraded,
        long       stalenessSeconds
) {}
