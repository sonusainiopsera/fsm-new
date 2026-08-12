package com.fieldservice.analytics.internal.sla;

import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * KpiAggregator for {@code sla.resolution.median}: median elapsed minutes from creation
 * to closure per priority tier and ALL rollup.
 *
 * <p>Median is pre-computed in {@link SlaAggregationRepository} via PostgreSQL
 * {@code percentile_cont(0.5) WITHIN GROUP (ORDER BY ...)}. This class reads the
 * pre-computed value so unit tests that inject hand-built {@link SlaAggregationRow}
 * objects produce results consistent with the database query definition.
 */
@Component
public class SlaResolutionMedianCalculator extends ResolutionTimeCalculator {

    static final String METRIC_KEY = "sla.resolution.median";

    SlaResolutionMedianCalculator(SlaAggregationRepository repo, Clock clock) {
        super(repo, clock);
    }

    @Override
    public String metricKey() { return METRIC_KEY; }

    @Override
    double extractValue(SlaAggregationRow row) {
        return row.medianResolutionMinutes();
    }
}
