package com.fieldservice.analytics.internal.workforce;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.UUID;

/**
 * Replica-routed aggregation queries for workforce utilization and throughput (WO-163).
 *
 * <p>Named parameters only — no dynamic SQL string construction (policy A05).
 *
 * <p>ISO-8601 week bucketing: ISO year and week extracted via PostgreSQL
 * {@code EXTRACT(ISOYEAR FROM ...)} and {@code EXTRACT(WEEK FROM ...)}; these follow
 * ISO-8601 (week starts Monday, week 1 contains the first Thursday). Java-side
 * {@link WeekFields#ISO} is used in tests and calculators with an injected Clock.
 *
 * <p>Roster/shift data: no roster or shift table exists in this schema. All labour-based
 * queries return only logged field time. Callers mark utilization rows as
 * {@code is_incomplete_data = true} when the hours-worked denominator is absent.
 *
 * <p>Active-day proxy: in the absence of a roster table, an active technician-day is
 * defined as any date on which the technician has at least one {@code labour_time_record}
 * row. This is consistent with {@link ActiveTechnicianDayResolver}'s documented fallback.
 */
@Component
class WorkforceAggregationRepository {

    /**
     * Per-technician per-ISO-week aggregate of logged field minutes.
     *
     * <p>Columns: technician_id, iso_year, iso_week, field_minutes, distinct_days.
     */
    private static final String LABOUR_BY_TECH_WEEK_SQL =
            "SELECT " +
            "  ltr.technician_id, " +
            "  EXTRACT(ISOYEAR FROM ltr.work_date AT TIME ZONE :zone)::int    AS iso_year, " +
            "  EXTRACT(WEEK    FROM ltr.work_date AT TIME ZONE :zone)::int    AS iso_week, " +
            "  SUM(ltr.minutes)::int                                           AS field_minutes, " +
            "  COUNT(DISTINCT DATE(ltr.work_date AT TIME ZONE :zone))::int    AS distinct_days " +
            "FROM labour_time_record ltr " +
            "WHERE ltr.work_date >= :windowStart " +
            "  AND ltr.work_date <  :windowEnd " +
            "GROUP BY ltr.technician_id, iso_year, iso_week";

    /**
     * Per-technician per-date count of work orders closed (COMPLETED or CLOSED state).
     *
     * <p>Uses {@code updated_at} as the closure timestamp (consistent with SLA queries).
     */
    private static final String CLOSURES_BY_TECH_DATE_SQL =
            "SELECT " +
            "  wo.assigned_technician_id                                        AS technician_id, " +
            "  DATE(wo.updated_at AT TIME ZONE :zone)                          AS closure_date, " +
            "  COUNT(*)::int                                                    AS closure_count " +
            "FROM work_order wo " +
            "WHERE wo.state IN ('COMPLETED', 'CLOSED') " +
            "  AND wo.assigned_technician_id IS NOT NULL " +
            "  AND wo.updated_at >= :windowStart " +
            "  AND wo.updated_at <  :windowEnd " +
            "GROUP BY wo.assigned_technician_id, closure_date";

    /**
     * Per-technician count of distinct active days (dates with ≥1 logged field minute)
     * within the window.
     */
    private static final String ACTIVE_DAYS_BY_TECH_SQL =
            "SELECT " +
            "  ltr.technician_id, " +
            "  COUNT(DISTINCT DATE(ltr.work_date AT TIME ZONE :zone))::int    AS active_days " +
            "FROM labour_time_record ltr " +
            "WHERE ltr.work_date >= :windowStart " +
            "  AND ltr.work_date <  :windowEnd " +
            "GROUP BY ltr.technician_id";

    private final NamedParameterJdbcTemplate replicaJdbc;

    WorkforceAggregationRepository(
            @Qualifier("replicaNamedParameterJdbcTemplate") NamedParameterJdbcTemplate replicaJdbc) {
        this.replicaJdbc = replicaJdbc;
    }

    /**
     * Returns per-technician per-ISO-week labour aggregates within the window.
     *
     * @param now       window end (exclusive)
     * @param days      rolling window length in days
     * @param zone      configured zone for ISO-week bucketing
     */
    List<LabourWeekRow> queryLabourByTechnicianWeek(Instant now, int days, ZoneId zone) {
        MapSqlParameterSource params = windowParams(now, days).addValue("zone", zone.getId());
        return replicaJdbc.query(LABOUR_BY_TECH_WEEK_SQL, params, (rs, rowNum) ->
                new LabourWeekRow(
                        rs.getObject("technician_id", UUID.class),
                        rs.getInt("iso_year"),
                        rs.getInt("iso_week"),
                        rs.getInt("field_minutes"),
                        rs.getInt("distinct_days")));
    }

    /**
     * Returns per-technician per-date closure counts within the window.
     */
    List<ClosureRow> queryClosuresByTechnicianDate(Instant now, int days, ZoneId zone) {
        MapSqlParameterSource params = windowParams(now, days).addValue("zone", zone.getId());
        return replicaJdbc.query(CLOSURES_BY_TECH_DATE_SQL, params, (rs, rowNum) ->
                new ClosureRow(
                        rs.getObject("technician_id", UUID.class),
                        rs.getObject("closure_date", LocalDate.class),
                        rs.getInt("closure_count")));
    }

    /**
     * Returns per-technician active-day counts within the window (active = any logged time).
     */
    List<ActiveDayRow> queryActiveDaysByTechnician(Instant now, int days, ZoneId zone) {
        MapSqlParameterSource params = windowParams(now, days).addValue("zone", zone.getId());
        return replicaJdbc.query(ACTIVE_DAYS_BY_TECH_SQL, params, (rs, rowNum) ->
                new ActiveDayRow(
                        rs.getObject("technician_id", UUID.class),
                        rs.getInt("active_days")));
    }

    // ── Query result row types ────────────────────────────────────────────────

    /**
     * Per-technician per-ISO-week logged field minutes aggregate.
     *
     * @param technicianId  UUID of the technician (no PII per BR-23)
     * @param isoYear       ISO-8601 year component of the work week
     * @param isoWeek       ISO-8601 week number (1–53)
     * @param fieldMinutes  sum of logged minutes for field tasks in this week
     * @param distinctDays  distinct calendar days with at least one logged entry this week
     */
    record LabourWeekRow(UUID technicianId, int isoYear, int isoWeek,
                         int fieldMinutes, int distinctDays) {}

    /**
     * Per-technician per-date work-order closure count.
     *
     * @param technicianId  UUID of the assigned technician (no PII per BR-23)
     * @param closureDate   calendar date of closure (in the configured zone)
     * @param closureCount  number of work orders closed on this date by this technician
     */
    record ClosureRow(UUID technicianId, LocalDate closureDate, int closureCount) {}

    /**
     * Per-technician active-day count (days with ≥1 logged field minute).
     */
    record ActiveDayRow(UUID technicianId, int activeDays) {}

    // ── Private helpers ───────────────────────────────────────────────────────

    private static MapSqlParameterSource windowParams(Instant now, int days) {
        Instant windowStart = now.minus(days, java.time.temporal.ChronoUnit.DAYS);
        return new MapSqlParameterSource()
                .addValue("windowStart", Timestamp.from(windowStart))
                .addValue("windowEnd",   Timestamp.from(now));
    }
}
