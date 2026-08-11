package com.fieldservice.analytics.internal.quality;

import com.fieldservice.analytics.internal.KpiAggregator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the provisional first-time fix rate.
 *
 * <p>This metric carries a maturity label of PROVISIONAL and must never be
 * presented as settled fact (BR-30). The rate is computed over work orders
 * still within their 30-day observation window.
 */
@Component
public class FirstTimeFixProvisionalAggregator implements KpiAggregator {

    static final String METRIC_KEY = "quality.first_time_fix.provisional";

    private final org.springframework.jdbc.core.JdbcTemplate analyticsJdbc;
    private final Clock clock;

    public FirstTimeFixProvisionalAggregator(
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

            Map<String, Object> row = analyticsJdbc.queryForMap("""
                    SELECT
                        COUNT(*)                                             AS denominator,
                        SUM(CASE WHEN is_first_time_fix THEN 1 ELSE 0 END)  AS numerator,
                        MAX(closed_at)                                       AS data_as_of
                    FROM analytics_closure_projection
                    WHERE maturity  = 'PROVISIONAL'
                      AND asset_id  IS NOT NULL
                      AND fault_key IS NOT NULL
                      AND closed_at >= ?
                    """, java.sql.Timestamp.from(windowStart));

            long    denominator = toLong(row.get("denominator"));
            long    numerator   = toLong(row.get("numerator"));
            Object  rawAsOf     = row.get("data_as_of");
            Instant dataAsOf    = rawAsOf instanceof java.sql.Timestamp ts
                    ? ts.toInstant() : clock.instant();

            BigDecimal value = denominator == 0 ? null
                    : new BigDecimal(numerator)
                            .divide(new BigDecimal(denominator), 4, RoundingMode.HALF_UP);

            results.add(new KpiAggregatorResult(
                    "ALL",
                    windowKey,
                    BigDecimal.valueOf(numerator),
                    denominator == 0 ? null : BigDecimal.valueOf(denominator),
                    value,
                    (int) denominator,
                    "PROVISIONAL",
                    dataAsOf
            ));
        }

        return results;
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
