package com.fieldservice.analytics;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Public read port for the analytics KPI read model.
 *
 * <p>All implementations are package-private and live in
 * {@code com.fieldservice.analytics.internal}. Callers in the REST layer depend only on
 * this interface — never on any class in the {@code internal} sub-package.
 *
 * <p>Every returned {@link KpiProjection} carries a {@code degraded} flag. Callers must
 * propagate this flag to the UI; they must never suppress it or substitute a synthesized
 * fresh-looking value.
 */
public interface KpiProjectionQuery {

    /**
     * Returns the current projection for a single (metric, segment, window) triple.
     * Returns an empty Optional if no projection has been computed yet.
     */
    Optional<KpiProjection> findProjection(String metricKey, String segmentKey, String windowKey);

    /**
     * Returns all current projections for a metric key across all segments and windows.
     */
    List<KpiProjection> findAllByMetricKey(String metricKey);

    /**
     * Returns all projections that are currently marked degraded, for operational dashboards.
     */
    List<KpiProjection> findDegraded();

    /**
     * Returns the most recent {@code limit} daily trend points for a (metric, segment) pair,
     * ordered by bucket date descending. Returns an empty list when no trend data exists yet.
     */
    List<TrendPointDto> findRecentTrendPoints(String metricKey, String segmentKey, int limit);

    /**
     * Returns the captured baseline value for a (metric, segment, window) triple, or empty
     * when no baseline row has been captured yet. Empty maps to {@code BASELINE_PENDING}.
     */
    Optional<BigDecimal> findBaseline(String metricKey, String segmentKey, String windowKey);
}
