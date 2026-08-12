package com.fieldservice.analytics.internal.workforce;

import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ClosureRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.LabourRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ShiftRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replica-routed JDBC repository for workforce utilization and throughput aggregations.
 *
 * <p>All queries use positional parameters only — no dynamic SQL string concatenation.
 *
 * <p>Labour minutes source: {@code work_order_labour_entry.minutes} grouped by technician and
 * ISO week (week truncated at the configured zone boundary).
 *
 * <p>Shift hours source: {@code technician_availability_window} expanded over the query window
 * using {@code generate_series} in PostgreSQL, summed per technician per ISO week.
 *
 * <p>Closure source: {@code analytics_closure_projection} joined to {@code work_order} for the
 * {@code assigned_technician_id} column.
 *
 * <p>Data classification: this repository returns technician UUIDs only — never names or
 * contact details, satisfying BR-23.
 */
@Repository
public class WorkforceAggregationRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkforceAggregationRepository.class);

    private final JdbcTemplate analyticsJdbc;

    WorkforceAggregationRepository(
            @Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbc) {
        this.analyticsJdbc = analyticsJdbc;
    }

    /**
     * Returns total logged field-task minutes per (technician, ISO week) within the window.
     * Uses {@code created_at} as the event timestamp for week bucketing.
     * Rows with a NULL {@code technician_id} are excluded.
     */
    List<LabourRow> queryLabourByTechWeek(Instant windowStart, Instant windowEnd) {
        String sql = """
                SELECT
                    le.technician_id::text           AS technician_id,
                    DATE_TRUNC('week', le.created_at) ::date AS iso_week_start,
                    SUM(le.minutes)                  AS field_minutes,
                    MAX(le.created_at)               AS data_as_of
                FROM work_order_labour_entry le
                WHERE le.technician_id IS NOT NULL
                  AND le.created_at >= ?
                  AND le.created_at  < ?
                GROUP BY le.technician_id,
                         DATE_TRUNC('week', le.created_at)
                ORDER BY iso_week_start, technician_id
                """;

        List<Map<String, Object>> rows = analyticsJdbc.queryForList(sql,
                Timestamp.from(windowStart), Timestamp.from(windowEnd));

        List<LabourRow> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new LabourRow(
                    UUID.fromString((String) row.get("technician_id")),
                    toLocalDate(row.get("iso_week_start")),
                    toLong(row.get("field_minutes")),
                    toInstant(row.get("data_as_of"))));
        }
        log.debug("workforce_labour_query window rows={}", result.size());
        return result;
    }

    /**
     * Returns total shift minutes per (technician, ISO week) by expanding
     * {@code technician_availability_window} over the query window via {@code generate_series}.
     *
     * <p>Each day in the window is matched against the recurring shift pattern if:
     * <ul>
     *   <li>The ISO day-of-week matches ({@code EXTRACT(ISODOW FROM day) = taw.day_of_week})</li>
     *   <li>The day falls within the effective range ({@code effective_from} … {@code effective_to})</li>
     * </ul>
     */
    List<ShiftRow> queryShiftByTechWeek(Instant windowStart, Instant windowEnd) {
        String sql = """
                SELECT
                    taw.technician_id::text                             AS technician_id,
                    DATE_TRUNC('week', day_series.day)::date            AS iso_week_start,
                    SUM(EXTRACT(EPOCH FROM (taw.end_time - taw.start_time)) / 60.0) AS shift_minutes
                FROM technician_availability_window taw
                CROSS JOIN LATERAL (
                    SELECT gs::date AS day
                    FROM generate_series(
                        ?::timestamptz::date,
                        (?::timestamptz - INTERVAL '1 second')::date,
                        '1 day'::interval
                    ) gs
                ) day_series
                WHERE EXTRACT(ISODOW FROM day_series.day) = taw.day_of_week
                  AND day_series.day >= taw.effective_from
                  AND (taw.effective_to IS NULL OR day_series.day < taw.effective_to)
                GROUP BY taw.technician_id,
                         DATE_TRUNC('week', day_series.day)
                ORDER BY iso_week_start, technician_id
                """;

        List<Map<String, Object>> rows = analyticsJdbc.queryForList(sql,
                Timestamp.from(windowStart), Timestamp.from(windowEnd));

        List<ShiftRow> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new ShiftRow(
                    UUID.fromString((String) row.get("technician_id")),
                    toLocalDate(row.get("iso_week_start")),
                    toLong(row.get("shift_minutes"))));
        }
        log.debug("workforce_shift_query window rows={}", result.size());
        return result;
    }

    /**
     * Returns closure count per (technician, date) for work orders that were closed
     * in the given window. Uses {@code work_order.assigned_technician_id} as the
     * attribution; work orders with no assigned technician are excluded.
     */
    List<ClosureRow> queryClosuresByTechDate(Instant windowStart, Instant windowEnd) {
        String sql = """
                SELECT
                    wo.assigned_technician_id::text          AS technician_id,
                    acp.closed_at::date                      AS closure_date,
                    COUNT(*)                                 AS closure_count,
                    MAX(acp.closed_at)                       AS data_as_of
                FROM analytics_closure_projection acp
                JOIN work_order wo ON wo.id = acp.work_order_id
                WHERE wo.assigned_technician_id IS NOT NULL
                  AND acp.closed_at >= ?
                  AND acp.closed_at  < ?
                GROUP BY wo.assigned_technician_id,
                         acp.closed_at::date
                ORDER BY closure_date, technician_id
                """;

        List<Map<String, Object>> rows = analyticsJdbc.queryForList(sql,
                Timestamp.from(windowStart), Timestamp.from(windowEnd));

        List<ClosureRow> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new ClosureRow(
                    UUID.fromString((String) row.get("technician_id")),
                    toLocalDate(row.get("closure_date")),
                    toLong(row.get("closure_count")),
                    toInstant(row.get("data_as_of"))));
        }
        log.debug("workforce_closure_query window rows={}", result.size());
        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof Date d) return d.toLocalDate();
        if (o instanceof java.time.LocalDate ld) return ld;
        return null;
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Timestamp ts) return ts.toInstant();
        return null;
    }
}
