package com.fieldservice.analytics.internal.quality;

import com.fieldservice.analytics.internal.KpiAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Computes the matured first-time fix rate segmented by asset category and window.
 *
 * <p>Formula: classifiable MATURED work orders with {@code is_first_time_fix = TRUE}
 * divided by all classifiable MATURED work orders in the window.
 * UNCLASSIFIABLE work orders (null asset_id or null fault_key) are excluded from
 * both numerator and denominator, and counted separately in
 * {@link UnclassifiableCountAggregator}.
 *
 * <p>Zero denominator (no matured classifiable WOs in window): {@code value = NULL}
 * rendered as "no-data" on the dashboard — never as 0 %.
 */
@Component
public class FirstTimeFixCalculator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(FirstTimeFixCalculator.class);

    static final String METRIC_KEY = "quality.first_time_fix.matured";
    static final int[]  WINDOW_DAYS = {90, 30};

    private final org.springframework.jdbc.core.JdbcTemplate analyticsJdbc;
    private final Clock clock;

    public FirstTimeFixCalculator(
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

        for (int windowDays : WINDOW_DAYS) {
            String windowKey   = "P" + windowDays + "D";
            Instant windowStart = now.minus(windowDays, ChronoUnit.DAYS);

            // ALL segment
            results.add(computeSegment("ALL", windowKey, windowStart, null));

            // Per-category segments
            List<String> categories = fetchDistinctCategories(windowStart);
            for (String cat : categories) {
                results.add(computeSegment("CATEGORY:" + cat, windowKey, windowStart, cat));
            }
        }

        return results;
    }

    private KpiAggregatorResult computeSegment(String segmentKey, String windowKey,
                                                Instant windowStart, String category) {
        String baseSql = """
                SELECT
                    COUNT(*)                                             AS denominator,
                    SUM(CASE WHEN is_first_time_fix THEN 1 ELSE 0 END)  AS numerator,
                    MAX(closed_at)                                       AS data_as_of
                FROM analytics_closure_projection
                WHERE maturity  = 'MATURED'
                  AND asset_id  IS NOT NULL
                  AND fault_key IS NOT NULL
                  AND closed_at >= ?
                """;

        Object[] params;
        if (category != null) {
            baseSql += " AND COALESCE(asset_category, 'UNKNOWN') = ?";
            params = new Object[]{java.sql.Timestamp.from(windowStart), category};
        } else {
            params = new Object[]{java.sql.Timestamp.from(windowStart)};
        }

        Map<String, Object> row = analyticsJdbc.queryForMap(baseSql, params);

        long   denominator = toLong(row.get("denominator"));
        long   numerator   = toLong(row.get("numerator"));
        Object rawAsOf     = row.get("data_as_of");
        Instant dataAsOf  = rawAsOf instanceof java.sql.Timestamp ts
                ? ts.toInstant()
                : clock.instant();

        BigDecimal value = denominator == 0 ? null
                : new BigDecimal(numerator)
                        .divide(new BigDecimal(denominator), 4, RoundingMode.HALF_UP);

        log.debug("ftf_matured_segment segment={} window={} numerator={} denominator={}",
                segmentKey, windowKey, numerator, denominator);

        return new KpiAggregatorResult(
                segmentKey,
                windowKey,
                BigDecimal.valueOf(numerator),
                denominator == 0 ? null : BigDecimal.valueOf(denominator),
                value,
                (int) denominator,
                "MATURE",
                dataAsOf
        );
    }

    private List<String> fetchDistinctCategories(Instant windowStart) {
        return analyticsJdbc.queryForList("""
                SELECT DISTINCT COALESCE(asset_category, 'UNKNOWN')
                  FROM analytics_closure_projection
                 WHERE maturity  = 'MATURED'
                   AND asset_id  IS NOT NULL
                   AND fault_key IS NOT NULL
                   AND closed_at >= ?
                """, String.class, java.sql.Timestamp.from(windowStart));
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
