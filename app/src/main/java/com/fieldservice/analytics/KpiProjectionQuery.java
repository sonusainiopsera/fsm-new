package com.fieldservice.analytics;

import java.util.List;
import java.util.Optional;

/**
 * Public port for reading KPI projections from the analytics read model.
 *
 * <p>Implementations serve values from the Redis cache where available,
 * falling back to the projection table on cache miss. A Redis or replica
 * outage degrades gracefully: the returned projection carries
 * {@code degraded = true} but is never null or exception-surfaced.
 *
 * <p>All other analytics internals (entities, repositories, consumers,
 * schedulers) are package-private to the {@code analytics.internal} package.
 */
public interface KpiProjectionQuery {

    /**
     * Returns the latest projection for the given (metric, segment, window) triple,
     * or empty if no projection has been computed yet.
     */
    Optional<KpiProjection> findByKey(String metricKey, String segmentKey, String windowKey);

    /**
     * Returns all current projections for the given metric key across every
     * (segment, window) combination that has been computed.
     */
    List<KpiProjection> findByMetricKey(String metricKey);
}
