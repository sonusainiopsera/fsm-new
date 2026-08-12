package com.fieldservice.analytics.internal.sla;

import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * KpiAggregator for {@code sla.resolution.mean}: volume-weighted mean elapsed minutes
 * from creation to closure, per priority tier and ALL rollup.
 */
@Component
public class SlaResolutionMeanCalculator extends ResolutionTimeCalculator {

    static final String METRIC_KEY = "sla.resolution.mean";

    SlaResolutionMeanCalculator(SlaAggregationRepository repo, Clock clock) {
        super(repo, clock);
    }

    @Override
    public String metricKey() { return METRIC_KEY; }

    @Override
    double extractValue(SlaAggregationRow row) {
        return row.meanResolutionMinutes();
    }
}
