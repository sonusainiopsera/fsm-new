package com.fieldservice.analytics.internal.backlog;

import com.fieldservice.analytics.internal.KpiAggregator;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes the current open-backlog count segmented by lifecycle state and priority tier.
 *
 * <p>The open-state set is derived from {@link WorkOrderStatus#openStates()} so a
 * lifecycle vocabulary change propagates here automatically.  Produces a reconciling
 * ALL total that always equals the sum of all STATE:* segment values.
 *
 * <p>windowKey is "CURRENT" — this is a point-in-time count, not a windowed rate.
 * Historical daily snapshots are written separately by {@link BacklogTrendWriterJob}.
 */
@Component
public class BacklogCalculator implements KpiAggregator {

    private static final Logger log = LoggerFactory.getLogger(BacklogCalculator.class);

    static final String METRIC_KEY = "backlog.open.count";
    static final String WINDOW_KEY = "CURRENT";

    private final JdbcTemplate analyticsJdbc;
    private final Clock        clock;

    public BacklogCalculator(
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

        // Open-state SQL fragment derived from published vocabulary — no literal list in analytics code
        String stateIn = WorkOrderStatus.openStates().stream()
                .map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(","));

        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                "SELECT state, priority, COUNT(*) AS cnt " +
                "FROM work_order WHERE state IN (" + stateIn + ") " +
                "GROUP BY state, priority");

        Map<String, Long> stateTotals    = new HashMap<>();
        Map<String, Long> priorityTotals = new HashMap<>();
        long total = 0L;

        for (Map<String, Object> row : rows) {
            String state    = (String) row.get("state");
            String priority = (String) row.get("priority");
            long   cnt      = toLong(row.get("cnt"));
            total += cnt;
            stateTotals.merge(state, cnt, Long::sum);
            priorityTotals.merge(priority, cnt, Long::sum);
        }

        List<KpiAggregatorResult> results = new ArrayList<>();

        // ALL — reconciling total; must equal sum of STATE:* values
        results.add(countResult("ALL", total, (int) total, now));

        // Per state
        stateTotals.forEach((state, count) ->
                results.add(countResult("STATE:" + state, count, count.intValue(), now)));

        // Per priority
        priorityTotals.forEach((priority, count) ->
                results.add(countResult("PRIORITY:" + priority, count, count.intValue(), now)));

        log.debug("backlog_open_count total={} states={} priorities={}",
                total, stateTotals.size(), priorityTotals.size());

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
