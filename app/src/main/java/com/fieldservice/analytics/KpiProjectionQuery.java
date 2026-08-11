package com.fieldservice.analytics;

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
}
