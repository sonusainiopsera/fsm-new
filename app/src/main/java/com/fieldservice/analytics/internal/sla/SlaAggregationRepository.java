package com.fieldservice.analytics.internal.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Replica-routed JDBC repository for SLA compliance and resolution time aggregations.
 *
 * <p>All queries use positional parameters only — no dynamic SQL string building,
 * satisfying policy A05. The compliance definition (closed at or before committed deadline)
 * reads {@code wo.resolution_deadline} which is derived from {@code sla_policy} at work-order
 * creation time; no threshold literal appears in this class.
 *
 * <p>Median is computed in PostgreSQL via {@code percentile_cont(0.5)} to avoid loading
 * all individual resolution durations into the JVM. The {@link ResolutionTimeCalculator}
 * uses the same percentile definition for unit-test parity.
 */
@Repository
public class SlaAggregationRepository {

    private static final Logger log = LoggerFactory.getLogger(SlaAggregationRepository.class);

    private final JdbcTemplate analyticsJdbc;

    SlaAggregationRepository(
            @Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbc) {
        this.analyticsJdbc = analyticsJdbc;
    }

    /**
     * Returns one {@link SlaAggregationRow} per priority tier for work orders closed
     * in [{@code windowStart}, {@code windowEnd}).
     *
     * <p>Work orders with a NULL {@code resolution_deadline} are counted in
     * {@code closedCount} but not in {@code compliantCount} — they cannot be evaluated
     * for compliance and are reported as a POLICY_MISSING degraded segment by the caller.
     */
    List<SlaAggregationRow> queryWindowedAggregation(
            Instant windowStart, Instant windowEnd, String windowKey) {

        String sql = """
                SELECT
                    wo.priority,
                    COUNT(*)                                                                    AS closed_count,
                    SUM(CASE
                        WHEN wo.resolution_deadline IS NOT NULL
                             AND acp.closed_at <= wo.resolution_deadline
                        THEN 1 ELSE 0 END)                                                     AS compliant_count,
                    COUNT(b.id) FILTER (WHERE b.breach_type = 'RESOLUTION')                    AS breach_count,
                    COALESCE(AVG(
                        EXTRACT(EPOCH FROM (acp.closed_at - wo.created_at)) / 60.0
                    ), 0)                                                                       AS mean_resolution_minutes,
                    COALESCE(percentile_cont(0.5) WITHIN GROUP (
                        ORDER BY EXTRACT(EPOCH FROM (acp.closed_at - wo.created_at)) / 60.0
                    ), 0)                                                                       AS median_resolution_minutes,
                    COALESCE(SUM(b.final_overrun_minutes)
                        FILTER (WHERE b.breach_type = 'RESOLUTION'), 0)                        AS total_overrun_minutes,
                    MAX(acp.closed_at)                                                          AS data_as_of
                FROM analytics_closure_projection acp
                JOIN work_order                   wo ON wo.id           = acp.work_order_id
                LEFT JOIN sla_breach              b  ON b.work_order_id = wo.id
                WHERE acp.closed_at >= ?
                  AND acp.closed_at  < ?
                GROUP BY wo.priority
                """;

        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                sql,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd));

        List<SlaAggregationRow> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String  priority     = (String) row.get("priority");
            long    closedCount  = toLong(row.get("closed_count"));
            long    compliant    = toLong(row.get("compliant_count"));
            long    breachCount  = toLong(row.get("breach_count"));
            double  meanMin      = toDouble(row.get("mean_resolution_minutes"));
            double  medianMin    = toDouble(row.get("median_resolution_minutes"));
            long    overrun      = toLong(row.get("total_overrun_minutes"));
            Instant dataAsOf     = toInstant(row.get("data_as_of"));
            if (dataAsOf == null) {
                dataAsOf = windowEnd;
            }
            result.add(new SlaAggregationRow(
                    priority, windowKey, closedCount, compliant, breachCount,
                    meanMin, medianMin, overrun, dataAsOf));
        }

        log.debug("sla_agg_query window={} rows={}", windowKey, result.size());
        return result;
    }

    /**
     * Returns breach counts grouped by (priority, reason_code) for work orders closed
     * in [{@code windowStart}, {@code windowEnd}).
     */
    List<SlaBreachReasonRow> queryBreachByReason(
            Instant windowStart, Instant windowEnd, String windowKey) {

        String sql = """
                SELECT
                    wo.priority,
                    b.reason_code,
                    COUNT(*)                                AS breach_count,
                    COALESCE(SUM(b.final_overrun_minutes), 0) AS total_overrun_minutes
                FROM sla_breach                   b
                JOIN analytics_closure_projection acp ON acp.work_order_id = b.work_order_id
                JOIN work_order                   wo  ON wo.id              = acp.work_order_id
                WHERE b.breach_type  = 'RESOLUTION'
                  AND acp.closed_at >= ?
                  AND acp.closed_at  < ?
                GROUP BY wo.priority, b.reason_code
                """;

        List<Map<String, Object>> rows = analyticsJdbc.queryForList(
                sql,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd));

        List<SlaBreachReasonRow> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new SlaBreachReasonRow(
                    (String) row.get("priority"),
                    windowKey,
                    (String) row.get("reason_code"),
                    toLong(row.get("breach_count")),
                    toLong(row.get("total_overrun_minutes"))));
        }
        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }

    private static Instant toInstant(Object o) {
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }
}
