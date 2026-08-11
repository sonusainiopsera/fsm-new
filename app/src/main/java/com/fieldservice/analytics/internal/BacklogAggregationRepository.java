package com.fieldservice.analytics.internal;

import com.fieldservice.domain.workorder.WorkOrderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Replica-routed aggregation queries for backlog metrics (WO-165).
 *
 * <h3>Open-state derivation</h3>
 * The open-state set is derived from the {@link WorkOrderState} enum by excluding terminal
 * states. This means a new non-terminal state added to the lifecycle is automatically counted
 * in the backlog without a code change here.
 */
@Component
class BacklogAggregationRepository {

    private static final Logger log = LoggerFactory.getLogger(BacklogAggregationRepository.class);

    /**
     * Terminal states excluded from the open backlog.
     * Any state NOT in this set is considered "open".
     */
    static final Set<WorkOrderState> TERMINAL_STATES = EnumSet.of(
            WorkOrderState.COMPLETED, WorkOrderState.CLOSED, WorkOrderState.CANCELLED);

    static final Set<WorkOrderState> OPEN_STATES = EnumSet.complementOf(
            (EnumSet<WorkOrderState>) TERMINAL_STATES);

    private final JdbcTemplate replicaJdbcTemplate;

    BacklogAggregationRepository(
            @Qualifier("replicaJdbcTemplate") JdbcTemplate replicaJdbcTemplate) {
        this.replicaJdbcTemplate = replicaJdbcTemplate;
    }

    /**
     * Total count of work orders in any open state.
     */
    @Nullable
    KpiAggregationQueries.AggregateResult queryTotalOpenCount() {
        String inClause = buildInClause(OPEN_STATES);
        try {
            Long count = replicaJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM work_order WHERE state IN (" + inClause + ")",
                    Long.class);
            if (count == null) count = 0L;
            return new KpiAggregationQueries.AggregateResult(
                    BigDecimal.valueOf(count), BigDecimal.valueOf(count), BigDecimal.ONE, count.intValue());
        } catch (Exception ex) {
            log.error("backlog.total_open.error — {}", ex.getMessage());
            throw ex;
        }
    }

    /**
     * Count of work orders specifically in ON_HOLD state.
     */
    KpiAggregationQueries.AggregateResult queryOnHoldCount() {
        try {
            Long count = replicaJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM work_order WHERE state = 'ON_HOLD'",
                    Long.class);
            if (count == null) count = 0L;
            return new KpiAggregationQueries.AggregateResult(
                    BigDecimal.valueOf(count), BigDecimal.valueOf(count), BigDecimal.ONE, count.intValue());
        } catch (Exception ex) {
            log.error("backlog.on_hold.error — {}", ex.getMessage());
            throw ex;
        }
    }

    /**
     * Backlog count segmented by state, priority and hold reason.
     *
     * <p>Returns one {@link BacklogSegment} per distinct (state, priority, hold_reason) group
     * present in the open backlog. Non-ON_HOLD work orders have {@code holdReason=null}.
     */
    List<BacklogSegment> querySegmentedBacklog() {
        String inClause = buildInClause(OPEN_STATES);
        try {
            return replicaJdbcTemplate.query(
                    "SELECT wo.state, wo.priority, woh.reason_code AS hold_reason, COUNT(*) AS cnt " +
                    "FROM work_order wo " +
                    "LEFT JOIN work_order_hold woh " +
                    "  ON woh.work_order_id = wo.id AND woh.ended_at IS NULL " +
                    "WHERE wo.state IN (" + inClause + ") " +
                    "GROUP BY wo.state, wo.priority, woh.reason_code",
                    (rs, row) -> new BacklogSegment(
                            rs.getString("state"),
                            rs.getString("priority"),
                            rs.getString("hold_reason"),
                            rs.getLong("cnt")));
        } catch (Exception ex) {
            log.error("backlog.segmented.error — {}", ex.getMessage());
            throw ex;
        }
    }

    /**
     * Returns summed labour minutes per technician for work orders with any active assignment
     * in the last {@code windowDays} days. Used for workload balance CV computation.
     *
     * <p>Only technicians with at least one active assignment (is_current=true) are included.
     * Labour records for technicians without a current assignment are excluded.
     */
    List<Map<String, Object>> queryTechnicianHoursInWindow(int windowDays) {
        try {
            return replicaJdbcTemplate.queryForList(
                    "SELECT a.technician_id, " +
                    "       COALESCE(SUM(ltr.minutes), 0) AS total_minutes " +
                    "FROM assignment a " +
                    "LEFT JOIN labour_time_record ltr " +
                    "  ON ltr.technician_id = a.technician_id " +
                    "  AND ltr.work_date >= now() - (?::int || ' days')::INTERVAL " +
                    "WHERE a.is_current = true " +
                    "GROUP BY a.technician_id",
                    windowDays);
        } catch (Exception ex) {
            log.error("workload.technician_hours.error — {}", ex.getMessage());
            throw ex;
        }
    }

    // -------------------------------------------------------------------------

    private static String buildInClause(Set<WorkOrderState> states) {
        return states.stream()
                .map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** Value type for a single backlog segment row. */
    record BacklogSegment(
            String state,
            String priority,
            @Nullable String holdReason,
            long count) {}
}
