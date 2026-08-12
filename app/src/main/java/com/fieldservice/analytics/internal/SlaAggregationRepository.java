package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Replica-routed aggregation queries for SLA compliance and resolution time (WO-162).
 *
 * <p>Named parameters only — no dynamic SQL string construction (policy A05).
 *
 * <p>Median computation: {@code percentile_cont(0.5) WITHIN GROUP (ORDER BY elapsed_minutes)}
 * — a database-side ordered-set aggregate. This definition is documented and mirrored
 * in {@link ResolutionTimeCalculator} for unit-test parity.
 *
 * <p>Closure timestamp: {@code updated_at} is used as the closure timestamp because a work
 * order in COMPLETED/CLOSED only transitions to those states once, making updated_at
 * equivalent to closed_at for those rows (consistent with KpiAggregationQueries).
 */
@Component
class SlaAggregationRepository {

    private static final Logger log = LoggerFactory.getLogger(SlaAggregationRepository.class);

    private static final String COMPLIANCE_SQL =
            "SELECT " +
            "  wo.priority, " +
            "  COUNT(*)                                                                           AS total_closed, " +
            "  COUNT(*) FILTER (" +
            "    WHERE wo.resolution_due_at IS NULL OR wo.updated_at <= wo.resolution_due_at)    AS compliant_count, " +
            "  COUNT(sb.id)                                                                       AS breach_count, " +
            "  COALESCE(SUM(COALESCE(sb.final_overrun_minutes, sb.overrun_minutes)), 0)          AS total_overrun_minutes, " +
            "  AVG(COALESCE(sb.final_overrun_minutes, sb.overrun_minutes))::numeric              AS mean_overrun_minutes, " +
            "  AVG(EXTRACT(EPOCH FROM (wo.updated_at - wo.created_at)) / 60.0)::numeric         AS mean_resolution_minutes, " +
            "  percentile_cont(0.5) WITHIN GROUP " +
            "    (ORDER BY EXTRACT(EPOCH FROM (wo.updated_at - wo.created_at)) / 60.0)          AS median_resolution_minutes " +
            "FROM work_order wo " +
            "LEFT JOIN sla_breach sb " +
            "  ON sb.work_order_id = wo.id AND sb.breach_type = 'RESOLUTION' " +
            "WHERE wo.state IN ('COMPLETED', 'CLOSED') " +
            "  AND wo.updated_at >= :windowStart " +
            "  AND wo.updated_at < :windowEnd " +
            "  AND wo.excluded_from_sla_compliance = false " +
            "GROUP BY wo.priority";

    private static final String BREACH_REASON_SQL =
            "SELECT " +
            "  wo.priority, " +
            "  COALESCE(sb.reason_code, 'UNATTRIBUTED')                                          AS reason_code, " +
            "  COUNT(*)                                                                           AS breach_count, " +
            "  COALESCE(SUM(COALESCE(sb.final_overrun_minutes, sb.overrun_minutes)), 0)          AS total_overrun_minutes " +
            "FROM work_order wo " +
            "JOIN sla_breach sb " +
            "  ON sb.work_order_id = wo.id AND sb.breach_type = 'RESOLUTION' " +
            "WHERE wo.state IN ('COMPLETED', 'CLOSED') " +
            "  AND wo.updated_at >= :windowStart " +
            "  AND wo.updated_at < :windowEnd " +
            "  AND wo.excluded_from_sla_compliance = false " +
            "GROUP BY wo.priority, COALESCE(sb.reason_code, 'UNATTRIBUTED')";

    private final NamedParameterJdbcTemplate replicaJdbc;

    SlaAggregationRepository(
            @Qualifier("replicaNamedParameterJdbcTemplate") NamedParameterJdbcTemplate replicaJdbc) {
        this.replicaJdbc = replicaJdbc;
    }

    /**
     * Queries compliance and resolution aggregates for the current rolling window.
     *
     * @param now        the reference instant (typically Clock.instant())
     * @param windowDays number of days to look back (7, 30, or 90)
     */
    List<SlaAggRow> queryCurrentWindow(Instant now, int windowDays) {
        Instant start = now.minus(windowDays, ChronoUnit.DAYS);
        return queryWindow(start, now);
    }

    /**
     * Queries the prior equivalent period (immediately preceding window of identical length).
     *
     * @param now        the reference instant
     * @param windowDays number of days per period
     */
    List<SlaAggRow> queryPriorWindow(Instant now, int windowDays) {
        Instant end   = now.minus(windowDays, ChronoUnit.DAYS);
        Instant start = end.minus(windowDays, ChronoUnit.DAYS);
        return queryWindow(start, end);
    }

    /**
     * Queries breach reason breakdown for the current rolling window.
     *
     * @param now        the reference instant
     * @param windowDays number of days to look back
     */
    List<BreachReasonRow> queryBreachReasons(Instant now, int windowDays) {
        Instant start = now.minus(windowDays, ChronoUnit.DAYS);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("windowStart", Timestamp.from(start))
                .addValue("windowEnd",   Timestamp.from(now));
        try {
            return replicaJdbc.query(BREACH_REASON_SQL, params,
                    (rs, row) -> new BreachReasonRow(
                            rs.getString("priority"),
                            rs.getString("reason_code"),
                            rs.getLong("breach_count"),
                            rs.getLong("total_overrun_minutes")));
        } catch (Exception ex) {
            log.error("sla.breach_reasons.error: windowDays={} — {}", windowDays, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Returns the distinct priority values present in closed work orders.
     * Used to determine which SLA policy lookups to attempt.
     */
    List<String> queryDistinctClosedPriorities() {
        try {
            return replicaJdbc.getJdbcTemplate().query(
                    "SELECT DISTINCT priority FROM work_order " +
                    "WHERE state IN ('COMPLETED', 'CLOSED') AND excluded_from_sla_compliance = false",
                    (rs, i) -> rs.getString("priority"));
        } catch (Exception ex) {
            log.error("sla.distinct_priorities.error — {}", ex.getMessage());
            return List.of();
        }
    }

    private List<SlaAggRow> queryWindow(Instant start, Instant end) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("windowStart", Timestamp.from(start))
                .addValue("windowEnd",   Timestamp.from(end));
        try {
            return replicaJdbc.query(COMPLIANCE_SQL, params,
                    (rs, rowNum) -> new SlaAggRow(
                            rs.getString("priority"),
                            rs.getLong("total_closed"),
                            rs.getLong("compliant_count"),
                            rs.getLong("breach_count"),
                            rs.getLong("total_overrun_minutes"),
                            nullableBigDecimal(rs, "mean_overrun_minutes"),
                            nullableBigDecimal(rs, "mean_resolution_minutes"),
                            nullableBigDecimal(rs, "median_resolution_minutes")));
        } catch (Exception ex) {
            log.error("sla.compliance.query.error — {}", ex.getMessage());
            throw ex;
        }
    }

    @Nullable
    private static BigDecimal nullableBigDecimal(java.sql.ResultSet rs, String col)
            throws java.sql.SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return rs.wasNull() ? null : v;
    }

    // ── Value records ──────────────────────────────────────────────────────────

    /**
     * One row per distinct priority from the main aggregation query.
     */
    record SlaAggRow(
            String priority,
            long totalClosed,
            long compliantCount,
            long breachCount,
            long totalOverrunMinutes,
            @Nullable BigDecimal meanOverrunMinutes,
            @Nullable BigDecimal meanResolutionMinutes,
            @Nullable BigDecimal medianResolutionMinutes) {

        /** Compliance rate in [0, 1]; null when totalClosed == 0 (no-data rather than 0%). */
        @Nullable BigDecimal complianceRate() {
            if (totalClosed == 0) return null;
            return BigDecimal.valueOf(compliantCount)
                    .divide(BigDecimal.valueOf(totalClosed), 4, java.math.RoundingMode.HALF_UP);
        }
    }

    /** Breach reason breakdown row. */
    record BreachReasonRow(
            String priority,
            String reasonCode,
            long breachCount,
            long totalOverrunMinutes) {}
}
