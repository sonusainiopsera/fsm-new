package com.fieldservice.analytics.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * SPI that KPI-specific work orders (WO-066 through WO-069) implement to supply
 * aggregated values for one metric key.
 *
 * <p>The analytics substrate calls {@link #compute()} after each debounce flush,
 * persists the results to {@code kpi_projection}, and updates the Redis cache.
 * Aggregations must use {@code @Qualifier("analyticsJdbcTemplate")} — the
 * replica-routed template — and must never query primary-domain entity tables
 * directly (read from {@code kpi_projection} or aggregated views only).
 *
 * <p>Implementations are registered as Spring beans and collected automatically.
 */
public interface KpiAggregator {

    /** Stable metric key this aggregator produces, e.g. {@code "wo.completion_rate"}. */
    String metricKey();

    /**
     * Computes current projections for all (segmentKey, windowKey) combinations
     * relevant to this metric.
     *
     * <p>Must be idempotent — may be called repeatedly without side effects beyond
     * populating the read model.
     *
     * @return one {@link KpiAggregatorResult} per (segment, window) pair; never null
     */
    List<KpiAggregatorResult> compute();

    record KpiAggregatorResult(
            String     segmentKey,
            String     windowKey,
            BigDecimal numerator,
            BigDecimal denominator,
            BigDecimal value,
            Integer    sampleCount,
            String     maturity,
            Instant    dataAsOf,
            boolean    partialBucket,
            boolean    incompleteData
    ) {
        /** Backward-compatible constructor for aggregators that do not use workforce flags. */
        KpiAggregatorResult(String segmentKey, String windowKey,
                             BigDecimal numerator, BigDecimal denominator,
                             BigDecimal value, Integer sampleCount,
                             String maturity, Instant dataAsOf) {
            this(segmentKey, windowKey, numerator, denominator, value,
                 sampleCount, maturity, dataAsOf, false, false);
        }
    }
}
