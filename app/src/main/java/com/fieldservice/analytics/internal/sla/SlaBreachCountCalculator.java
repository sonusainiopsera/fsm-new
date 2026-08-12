package com.fieldservice.analytics.internal.sla;

import com.fieldservice.analytics.internal.KpiAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes breach counts grouped by priority and breach reason code for the
 * {@code sla.breach.count} metric key.
 *
 * <p>Each result row carries: total breach count (numerator), total overrun minutes
 * (denominator), mean overrun (value), segmented by {@code "PRIORITY:HIGH:REASON:PARTS_UNAVAILABLE"}
 * style keys so the dashboard can render root-cause breakdowns.
 *
 * <p>The breach count plus the compliant count always equals the closed count for
 * the same window and segment — enforced in {@link SlaComplianceCalculator} and
 * verified by unit tests.
 */
@Component
public class SlaBreachCountCalculator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(SlaBreachCountCalculator.class);

    static final String METRIC_KEY  = "sla.breach.count";
    static final int[]  WINDOW_DAYS = {7, 30, 90};

    private final SlaAggregationRepository repo;
    private final Clock                    clock;

    SlaBreachCountCalculator(SlaAggregationRepository repo, Clock clock) {
        this.repo  = repo;
        this.clock = clock;
    }

    @Override
    public String metricKey() { return METRIC_KEY; }

    @Override
    public List<KpiAggregatorResult> compute() {
        Instant now     = clock.instant();
        List<KpiAggregatorResult> results = new ArrayList<>();

        for (int days : WINDOW_DAYS) {
            String  windowKey   = "P" + days + "D";
            Instant windowStart = now.minus(days, ChronoUnit.DAYS);

            List<SlaBreachReasonRow> reasonRows = repo.queryBreachByReason(windowStart, now, windowKey);

            long allBreachCount      = 0;
            long allTotalOverrunMins = 0;
            Instant dataAsOf         = now;

            for (SlaBreachReasonRow row : reasonRows) {
                String reasonKey  = row.reasonCode() != null ? row.reasonCode() : "UNATTRIBUTED";
                String segmentKey = "PRIORITY:" + row.priority() + ":REASON:" + reasonKey;

                long meanOverrun = row.breachCount() > 0
                        ? row.totalOverrunMinutes() / row.breachCount()
                        : 0L;

                results.add(new KpiAggregatorResult(
                        segmentKey,
                        windowKey,
                        BigDecimal.valueOf(row.breachCount()),
                        BigDecimal.valueOf(row.totalOverrunMinutes()),
                        BigDecimal.valueOf(meanOverrun),
                        (int) row.breachCount(),
                        null,
                        dataAsOf
                ));

                allBreachCount      += row.breachCount();
                allTotalOverrunMins += row.totalOverrunMinutes();
            }

            // ALL rollup
            if (allBreachCount > 0) {
                long allMeanOverrun = allBreachCount > 0
                        ? allTotalOverrunMins / allBreachCount : 0L;
                results.add(new KpiAggregatorResult(
                        "ALL",
                        windowKey,
                        BigDecimal.valueOf(allBreachCount),
                        BigDecimal.valueOf(allTotalOverrunMins),
                        BigDecimal.valueOf(allMeanOverrun),
                        (int) allBreachCount,
                        null,
                        dataAsOf
                ));
            }
        }

        log.debug("sla_breach_count_computed metric_key={} results={}", METRIC_KEY, results.size());
        return results;
    }
}
