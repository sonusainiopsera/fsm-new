package com.fieldservice.analytics.internal.workforce;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Raw aggregation rows returned by {@link WorkforceAggregationRepository}.
 * Package-private value types — not exposed beyond the workforce sub-package.
 */
final class WorkforceAggregationRow {

    private WorkforceAggregationRow() {}

    /**
     * Logged field-task minutes for one technician in one ISO week.
     *
     * @param technicianId  technician UUID
     * @param isoWeekStart  Monday of the ISO week (in the configured zone)
     * @param fieldMinutes  total minutes logged in work_order_labour_entry
     * @param dataAsOf      latest created_at in the aggregated window
     */
    record LabourRow(
            UUID      technicianId,
            LocalDate isoWeekStart,
            long      fieldMinutes,
            Instant   dataAsOf) {}

    /**
     * Available shift minutes for one technician in one ISO week derived
     * from technician_availability_window entries that overlap the query window.
     *
     * @param technicianId  technician UUID
     * @param isoWeekStart  Monday of the ISO week
     * @param shiftMinutes  total shift duration in minutes
     */
    record ShiftRow(
            UUID      technicianId,
            LocalDate isoWeekStart,
            long      shiftMinutes) {}

    /**
     * Closure count for one technician on one calendar date.
     *
     * @param technicianId  technician UUID (from work_order.assigned_technician_id)
     * @param date          UTC calendar date of closure
     * @param closureCount  number of work orders closed on that date
     * @param dataAsOf      latest closed_at in the aggregated date
     */
    record ClosureRow(
            UUID      technicianId,
            LocalDate date,
            long      closureCount,
            Instant   dataAsOf) {}

    /**
     * One date on which a technician had a rostered shift or logged field time.
     * Used by {@link ActiveTechnicianDayResolver} to count active technician-days.
     */
    record ActiveDayRow(
            UUID      technicianId,
            LocalDate date,
            boolean   hasShift,
            boolean   hasLoggedTime) {

        boolean isActive() { return hasShift || hasLoggedTime; }
    }
}
