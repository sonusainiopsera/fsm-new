package com.fieldservice.analytics.internal.quality;

import com.fieldservice.analytics.internal.KpiAggregator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts confirmed repeat visits (rows in {@code repeat_visit_link}) per window.
 */
@Component
public class RepeatVisitCountAggregator implements KpiAggregator {

    static final String METRIC_KEY = "quality.repeat_visit.count";

    private final org.springframework.jdbc.core.JdbcTemplate analyticsJdbc;
    private final Clock clock;

    public RepeatVisitCountAggregator(
            @Qualifier("analyticsJdbcTemplate")
            org.springframework.jdbc.core.JdbcTemplate analyticsJdbc,
            Clock clock) {
        this.analyticsJdbc = analyticsJdbc;
        this.clock         = clock;
    }

    @Override
    public String metricKey() {
        return METRIC_KEY;
    }

    @Override
    public List<KpiAggregatorResult> compute() {
        Instant now = clock.instant();
        List<KpiAggregatorResult> results = new ArrayList<>();

        for (int windowDays : FirstTimeFixCalculator.WINDOW_DAYS) {
            String  windowKey   = "P" + windowDays + "D";
            Instant windowStart = now.minus(windowDays, ChronoUnit.DAYS);

            Long count = analyticsJdbc.queryForObject(
                    "SELECT COUNT(*) FROM repeat_visit_link WHERE linked_at >= ?",
                    Long.class,
                    java.sql.Timestamp.from(windowStart));

            long n = count == null ? 0L : count;
            results.add(new KpiAggregatorResult(
                    "ALL",
                    windowKey,
                    BigDecimal.valueOf(n),
                    null,
                    BigDecimal.valueOf(n),
                    (int) n,
                    "MATURE",
                    clock.instant()
            ));
        }

        return results;
    }
}
