package com.fieldservice.analytics.internal.backlog;

import com.fieldservice.analytics.internal.KpiAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the count of work orders currently ON_HOLD, segmented by hold reason.
 *
 * <p>Hold reasons are joined from the {@code work_order_hold} table rather than
 * hardcoded, so a new reason added to the {@code hold_reason} vocabulary appears
 * automatically as a new segment on the next computation cycle.
 *
 * <p>windowKey is "CURRENT" — point-in-time count, not a windowed rate.
 */
@Component
public class BacklogOnHoldCountAggregator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(BacklogOnHoldCountAggregator.class);

    static final String METRIC_KEY = "backlog.on_hold.count";
    static final String WINDOW_KEY = "CURRENT";

    private final JdbcTemplate analyticsJdbc;
    private final Clock        clock;

    public BacklogOnHoldCountAggregator(
            @Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbc,
            Clock clock) {
        this.analyticsJdbc = analyticsJdbc;
        this.clock         = clock;
    }

    @Override
    public String metricKey() { return METRIC_KEY; }

    @Override
    public List<KpiAggregatorResult> compute() {
        Instant now = clock.instant();

        // Left-join work_order_hold to get active hold reason (ended_at IS NULL = open hold)
        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                "SELECT COALESCE(woh.reason_code, 'UNKNOWN') AS hold_reason, COUNT(*) AS cnt " +
                "FROM work_order wo " +
                "LEFT JOIN work_order_hold woh ON woh.work_order_id = wo.id AND woh.ended_at IS NULL " +
                "WHERE wo.state = 'ON_HOLD' " +
                "GROUP BY woh.reason_code");

        List<KpiAggregatorResult> results = new ArrayList<>();
        long total = 0L;

        for (Map<String, Object> row : rows) {
            String holdReason = (String) row.get("hold_reason");
            long   cnt        = toLong(row.get("cnt"));
            total += cnt;
            results.add(countResult("HOLD_REASON:" + holdReason, cnt, (int) cnt, now));
        }

        // Prepend the ALL total so it is always the first result
        results.add(0, countResult("ALL", total, (int) total, now));

        log.debug("backlog_on_hold_count total={} reasons={}", total, rows.size());

        return results;
    }

    private static KpiAggregatorResult countResult(String segmentKey, long count,
                                                    int sampleCount, Instant dataAsOf) {
        return new KpiAggregatorResult(
                segmentKey,
                WINDOW_KEY,
                BigDecimal.valueOf(count),
                null,
                BigDecimal.valueOf(count),
                sampleCount,
                null,
                dataAsOf
        );
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
