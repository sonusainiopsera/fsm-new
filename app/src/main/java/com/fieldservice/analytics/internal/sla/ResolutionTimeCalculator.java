package com.fieldservice.analytics.internal.sla;

import com.fieldservice.analytics.internal.KpiAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for resolution-time KPI aggregators.
 *
 * <p>Resolution time is the elapsed minutes from work-order creation to closure.
 * This class implements the window iteration, priority segmentation, weighted ALL rollup
 * and prior-period delta. Concrete subclasses supply the metric key and extract either
 * the mean or median value from each {@link SlaAggregationRow}.
 *
 * <p>Median is computed in PostgreSQL via {@code percentile_cont(0.5)} in
 * {@link SlaAggregationRepository}. This class uses the pre-computed value from the
 * row; the definition is identical so unit tests that inject hand-built rows produce
 * results consistent with the database query.
 *
 * <p>Zero-denominator: a window with no closed work orders returns {@code null} for
 * value and is rendered as "no-data" on the dashboard, never as 0.
 */
public abstract class ResolutionTimeCalculator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(ResolutionTimeCalculator.class);

    static final int[] WINDOW_DAYS = {7, 30, 90};

    protected final SlaAggregationRepository repo;
    protected final Clock                    clock;

    ResolutionTimeCalculator(SlaAggregationRepository repo, Clock clock) {
        this.repo  = repo;
        this.clock = clock;
    }

    /** Extracts the resolution time metric value from a query result row (mean or median). */
    abstract double extractValue(SlaAggregationRow row);

    @Override
    public List<KpiAggregatorResult> compute() {
        Instant now     = clock.instant();
        List<KpiAggregatorResult> results = new ArrayList<>();
        int totalSegments = 0;

        for (int days : WINDOW_DAYS) {
            String  windowKey   = "P" + days + "D";
            Instant windowStart = now.minus(days, ChronoUnit.DAYS);
            Instant priorStart  = windowStart.minus(days, ChronoUnit.DAYS);

            List<SlaAggregationRow> currentRows = repo.queryWindowedAggregation(windowStart, now, windowKey);
            List<SlaAggregationRow> priorRows   = repo.queryWindowedAggregation(priorStart, windowStart, windowKey);

            // Per-priority
            long   allClosed      = 0;
            double allWeightedSum = 0.0;
            Instant latestAsOf    = null;

            for (SlaAggregationRow row : currentRows) {
                if (row.closedCount() == 0) continue;

                String     segmentKey = "PRIORITY:" + row.priority();
                BigDecimal value      = BigDecimal.valueOf(extractValue(row)).setScale(2, RoundingMode.HALF_UP);
                Instant    asOf       = row.dataAsOf();

                results.add(new KpiAggregatorResult(
                        segmentKey, windowKey,
                        value, BigDecimal.valueOf(row.closedCount()),
                        value, (int) row.closedCount(), null, asOf));
                totalSegments++;

                // Prior-period delta
                priorRows.stream()
                         .filter(p -> p.priority().equals(row.priority()) && p.closedCount() > 0)
                         .findFirst()
                         .ifPresent(prior -> {
                             BigDecimal priorVal = BigDecimal.valueOf(extractValue(prior))
                                     .setScale(2, RoundingMode.HALF_UP);
                             BigDecimal delta = value.subtract(priorVal).setScale(2, RoundingMode.HALF_UP);
                             results.add(new KpiAggregatorResult(
                                     "DELTA:" + segmentKey, windowKey,
                                     value, priorVal, delta,
                                     (int) prior.closedCount(), null, asOf));
                         });

                allClosed      += row.closedCount();
                allWeightedSum += extractValue(row) * row.closedCount();
                if (latestAsOf == null || asOf.isAfter(latestAsOf)) latestAsOf = asOf;
            }

            // Weighted ALL rollup
            if (allClosed > 0) {
                Instant asOf = latestAsOf != null ? latestAsOf : now;
                BigDecimal allValue = BigDecimal.valueOf(allWeightedSum / allClosed)
                        .setScale(2, RoundingMode.HALF_UP);

                results.add(new KpiAggregatorResult(
                        "ALL", windowKey,
                        allValue, BigDecimal.valueOf(allClosed),
                        allValue, (int) allClosed, null, asOf));
                totalSegments++;

                // ALL prior-period delta
                long   priorAllClosed    = 0;
                double priorAllWeighted  = 0.0;
                for (SlaAggregationRow pr : priorRows) {
                    priorAllClosed   += pr.closedCount();
                    priorAllWeighted += extractValue(pr) * pr.closedCount();
                }
                if (priorAllClosed > 0) {
                    BigDecimal priorAllValue = BigDecimal.valueOf(priorAllWeighted / priorAllClosed)
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal delta = allValue.subtract(priorAllValue).setScale(2, RoundingMode.HALF_UP);
                    results.add(new KpiAggregatorResult(
                            "DELTA:ALL", windowKey,
                            allValue, priorAllValue, delta,
                            (int) priorAllClosed, null, asOf));
                    totalSegments++;
                }
            }
        }

        log.info("resolution_time_computed metric_key={} segment_count={}",
                metricKey(), totalSegments);
        return results;
    }
}
