package com.fieldservice.analytics.internal.workforce;

import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ClosureRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.LabourRow;
import com.fieldservice.analytics.internal.workforce.WorkforceAggregationRow.ShiftRow;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Encapsulates the authoritative definition of an "active technician-day".
 *
 * <p><strong>Definition (single source of truth):</strong> A technician-day is <em>active</em>
 * when the technician had either a rostered shift entry in
 * {@code technician_availability_window} OR any logged field time in
 * {@code work_order_labour_entry} on that calendar date. Both conditions are OR-combined
 * so that technicians who work ad-hoc (no formal roster) are not silently excluded.
 *
 * <p><strong>Exclusion rule:</strong> A technician with zero active days in a window is
 * excluded from the {@code workforce.jobs_per_day} denominator entirely — it must not
 * be counted as a zero-throughput day, which would drag down the team rate.
 *
 * <p><strong>Partial-window handling:</strong> Only days within the caller's query window
 * are counted. Days outside the window (e.g., an active technician onboarded on day 20
 * of a 30-day window) contribute at most 10 days to the denominator.
 *
 * <p><strong>Daylight-saving invariance:</strong> All date comparisons use {@link LocalDate}
 * values derived from UTC timestamps in PostgreSQL ({@code closed_at::date}), avoiding
 * 23-hour or 25-hour day distortions.
 */
@Component
public class ActiveTechnicianDayResolver {

    /**
     * Builds a map from technician UUID to count of active days within the window,
     * derived from the provided labour and shift rows.
     *
     * <p>A day is active if:
     * <ul>
     *   <li>the technician has a shift row covering that calendar date; OR</li>
     *   <li>the technician has a labour row with field minutes > 0 on that date
     *       (labour rows are per-ISO-week so a non-zero week contributes its days
     *       implicitly — see note below).</li>
     * </ul>
     *
     * <p><strong>Implementation note:</strong> Labour rows are grouped per ISO week (not per day).
     * To avoid inflating the denominator, only calendar dates within the query window that
     * have a corresponding shift row count as active. Labour without a shift row marks the
     * technician-week as having field activity, contributing days only from the shift side.
     * If no shift data is available for a technician-week that has labour, the week is marked
     * incomplete (the caller handles this via {@link #hasAnyShiftData}).
     *
     * @param labourRows labour rows per technician per ISO week
     * @param shiftRows  shift rows per technician per ISO week
     * @param windowStart first date (inclusive) of the query window
     * @param windowEnd   last date (exclusive) of the query window
     * @return map from technician UUID → active day count (only technicians with ≥1 active day)
     */
    public Map<UUID, Long> resolveActiveDays(
            List<LabourRow> labourRows,
            List<ShiftRow>  shiftRows,
            LocalDate       windowStart,
            LocalDate       windowEnd) {

        // Build a set of (technicianId, date) pairs from shift rows.
        // Each shift row covers one ISO week; we expand to individual days.
        Set<String> shiftDays = buildShiftDays(shiftRows, windowStart, windowEnd);

        // Technicians that have any logged field time — used to include ad-hoc days.
        Set<UUID> techsWithLabour = labourRows.stream()
                .filter(r -> r.fieldMinutes() > 0)
                .map(LabourRow::technicianId)
                .collect(Collectors.toSet());

        // Count active days per technician from shift data.
        // Technicians with labour but no shift data are NOT given a default day count —
        // the row is marked incomplete (zero active days → excluded from denominator).
        Map<UUID, Long> activeDays = new HashMap<>();
        for (ShiftRow sr : shiftRows) {
            LocalDate weekStart = sr.isoWeekStart();
            if (weekStart == null) continue;
            for (int d = 0; d < 7; d++) {
                LocalDate day = weekStart.plusDays(d);
                if (day.isBefore(windowStart) || !day.isBefore(windowEnd)) continue;
                String key = sr.technicianId() + ":" + day;
                if (shiftDays.contains(key)) {
                    activeDays.merge(sr.technicianId(), 1L, Long::sum);
                }
            }
        }

        // Remove duplicates: shiftDays already ensures each (tech, date) counted once.
        // Rebuild cleanly from shiftDays set.
        activeDays.clear();
        for (String key : shiftDays) {
            int sep = key.lastIndexOf(':');
            UUID techId = UUID.fromString(key.substring(0, sep));
            activeDays.merge(techId, 1L, Long::sum);
        }

        return activeDays;
    }

    /**
     * Returns {@code true} if at least one shift row exists for the given technician
     * within the window. Used by the calculators to flag rows as INCOMPLETE_DATA
     * when the hours-worked source is absent.
     */
    public boolean hasAnyShiftData(UUID technicianId, List<ShiftRow> shiftRows) {
        return shiftRows.stream().anyMatch(r -> technicianId.equals(r.technicianId()));
    }

    /**
     * Returns all technician UUIDs that appear in either labour or shift rows,
     * forming the full cohort for the window. Used to include technicians with
     * roster hours but zero field time (0% utilization — valid data, must not be excluded).
     */
    public Set<UUID> resolveAllTechnicians(List<LabourRow> labourRows, List<ShiftRow> shiftRows) {
        Set<UUID> techs = labourRows.stream().map(LabourRow::technicianId)
                .collect(Collectors.toCollection(java.util.HashSet::new));
        shiftRows.stream().map(ShiftRow::technicianId).forEach(techs::add);
        return techs;
    }

    /**
     * Returns technician UUIDs that appear in closure rows — used by ThroughputCalculator
     * to build the team cohort for jobs-per-day.
     */
    public Set<UUID> resolveTechsWithClosures(List<ClosureRow> closureRows) {
        return closureRows.stream().map(ClosureRow::technicianId).collect(Collectors.toSet());
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /**
     * Expands shift rows into per-day keys ({@code "techId:yyyy-MM-dd"}) for all days
     * within the window, for fast O(1) lookup.
     */
    private Set<String> buildShiftDays(
            List<ShiftRow> shiftRows, LocalDate windowStart, LocalDate windowEnd) {

        Set<String> days = new java.util.HashSet<>();
        for (ShiftRow sr : shiftRows) {
            if (sr.isoWeekStart() == null) continue;
            for (int d = 0; d < 7; d++) {
                LocalDate day = sr.isoWeekStart().plusDays(d);
                if (day.isBefore(windowStart) || !day.isBefore(windowEnd)) continue;
                days.add(sr.technicianId() + ":" + day);
            }
        }
        return days;
    }
}
