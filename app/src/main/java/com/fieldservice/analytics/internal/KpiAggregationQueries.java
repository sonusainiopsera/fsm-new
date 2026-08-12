package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aggregation queries executed against the read replica (WO-161).
 *
 * <p>All methods use the {@code replicaJdbcTemplate} bean so query load never touches
 * the primary write path. The primary is only used for projection upserts.
 *
 * <p>Named parameterised queries only — no string concatenation (policy A05). Each
 * method returns a {@link AggregateResult} record or null on empty dataset.
 *
 * <p>This class is a substrate: it implements three seed metrics that dependent WOs
 * (WO-066 to WO-069) will extend with their own metric-specific aggregation logic.
 * Metric keys are constants so callers and consumers can reference them without
 * magic strings.
 */
@Component
class KpiAggregationQueries {

    private static final Logger log = LoggerFactory.getLogger(KpiAggregationQueries.class);

    // --- Metric key constants --------------------------------------------------

    /** Work order open backlog count across all priorities. */
    static final String METRIC_WO_BACKLOG_COUNT = "workorder.backlog_count";
    /** 7-day work order completion rate (completed / closed_or_completed). */
    static final String METRIC_WO_COMPLETION_RATE_7D = "workorder.completion_rate_7d";
    /** 7-day first-contact SLA compliance rate. */
    static final String METRIC_WO_SLA_COMPLIANCE_7D = "workorder.sla_compliance_7d";

    static final String SEGMENT_ALL = "ALL";
    static final String WINDOW_ALL_TIME = "ALL_TIME";
    static final String WINDOW_ROLLING_7D = "ROLLING_7D";

    private final JdbcTemplate replicaJdbcTemplate;

    KpiAggregationQueries(@Qualifier("replicaJdbcTemplate") JdbcTemplate replicaJdbcTemplate) {
        this.replicaJdbcTemplate = replicaJdbcTemplate;
    }

    /**
     * Counts all open work orders (states: NEW, ASSIGNED, EN_ROUTE, IN_PROGRESS, ON_HOLD).
     */
    @Nullable
    AggregateResult queryBacklogCount() {
        try {
            Long count = replicaJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM work_order " +
                    "WHERE state IN ('NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD')",
                    Long.class);
            if (count == null) return null;
            return new AggregateResult(
                    BigDecimal.valueOf(count), BigDecimal.valueOf(count),
                    BigDecimal.ONE, count.intValue());
        } catch (Exception ex) {
            log.error("analytics.aggregate.backlog_count.error — {}", ex.getMessage());
            throw ex;
        }
    }

    /**
     * Computes the 7-day completion rate: completed / (completed + cancelled) in the window.
     */
    @Nullable
    AggregateResult queryCompletionRate7d() {
        try {
            Map<String, Object> row = replicaJdbcTemplate.queryForMap(
                    "SELECT " +
                    "  COUNT(*) FILTER (WHERE state IN ('COMPLETED', 'CLOSED'))    AS completed, " +
                    "  COUNT(*) FILTER (WHERE state NOT IN ('NEW', 'ASSIGNED'))    AS total " +
                    "FROM work_order " +
                    "WHERE updated_at >= now() - INTERVAL '7 days' " +
                    "  AND state NOT IN ('NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD')");

            long completed = toLong(row.get("completed"));
            long total     = toLong(row.get("total"));
            if (total == 0) return new AggregateResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);

            BigDecimal rate = BigDecimal.valueOf(completed).divide(
                    BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP);
            return new AggregateResult(rate, BigDecimal.valueOf(completed),
                    BigDecimal.valueOf(total), (int) total);
        } catch (Exception ex) {
            log.error("analytics.aggregate.completion_rate_7d.error — {}", ex.getMessage());
            throw ex;
        }
    }

    /**
     * Computes the 7-day SLA compliance rate: work orders not breached / total closed in window.
     */
    @Nullable
    AggregateResult querySlaCompliance7d() {
        try {
            Map<String, Object> row = replicaJdbcTemplate.queryForMap(
                    "SELECT " +
                    "  COUNT(*) FILTER (WHERE resolution_due_at IS NULL OR updated_at <= resolution_due_at) AS compliant, " +
                    "  COUNT(*) AS total " +
                    "FROM work_order " +
                    "WHERE state IN ('COMPLETED', 'CLOSED') " +
                    "  AND updated_at >= now() - INTERVAL '7 days' " +
                    "  AND excluded_from_sla_compliance = false");

            long compliant = toLong(row.get("compliant"));
            long total     = toLong(row.get("total"));
            if (total == 0) return new AggregateResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);

            BigDecimal rate = BigDecimal.valueOf(compliant).divide(
                    BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_UP);
            return new AggregateResult(rate, BigDecimal.valueOf(compliant),
                    BigDecimal.valueOf(total), (int) total);
        } catch (Exception ex) {
            log.error("analytics.aggregate.sla_compliance_7d.error — {}", ex.getMessage());
            throw ex;
        }
    }

    private static long toLong(@Nullable Object val) {
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    /**
     * Aggregate result: computed value, numerator, denominator, and sample count.
     */
    record AggregateResult(BigDecimal value, BigDecimal numerator,
                           BigDecimal denominator, int sampleCount) {}
}
