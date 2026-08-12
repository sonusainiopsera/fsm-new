package com.fieldservice.analytics.internal.workforce;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Encapsulates the active-technician-day definition for workforce KPI denominators (WO-163 AC-2).
 *
 * <h3>Authoritative definition</h3>
 * An <b>active technician-day</b> is a calendar date on which the technician had
 * a <em>rostered shift</em> OR any <em>logged field time</em>. This definition lives
 * here and nowhere else so every edge case (holidays, leave, partial weeks, onboarding)
 * is resolved in one testable place.
 *
 * <h3>Schema fallback — no roster table</h3>
 * The current schema does not include a roster or shift table. Until that data source
 * is available this resolver falls back to the logged-field-time half of the definition:
 * active days = distinct calendar dates with at least one {@code labour_time_record} row.
 * Every row produced under this fallback is marked {@code incompleteData = true} so the
 * dashboard can surface the caveat to operations managers.
 *
 * <h3>Zero-active-day exclusion (AC-2)</h3>
 * A technician with zero active days in the window is <em>excluded from the jobs-per-day
 * denominator</em>. They are not counted as "zero-throughput days" because they may have
 * been on leave or not yet onboarded. The returned map omits such technicians entirely so
 * callers apply {@code Map.containsKey} to distinguish "zero days" from "zero closures."
 *
 * <h3>Onboarding and deactivation (AC-4)</h3>
 * A technician onboarded or deactivated mid-window contributes only the days in which
 * they had logged time. No special handling is needed beyond the base query because the
 * {@code labour_time_record} table only contains rows for dates the technician was active.
 */
@Component
class ActiveTechnicianDayResolver {

    /**
     * True when this resolver is operating in roster-absent fallback mode (current schema state).
     * A false value would indicate roster data is available and the definition is fully honoured.
     */
    static final boolean ROSTER_DATA_AVAILABLE = false;

    /**
     * Derives per-technician active day counts from pre-aggregated active-day rows.
     *
     * <p>Technicians with zero active days are excluded from the returned map (AC-2).
     *
     * @param activeDayRows rows from {@link WorkforceAggregationRepository#queryActiveDaysByTechnician}
     * @return map from technician UUID to active-day count, omitting zero-day entries
     */
    Map<UUID, Integer> resolve(List<WorkforceAggregationRepository.ActiveDayRow> activeDayRows) {
        return activeDayRows.stream()
                .filter(row -> row.activeDays() > 0)
                .collect(Collectors.toMap(
                        WorkforceAggregationRepository.ActiveDayRow::technicianId,
                        WorkforceAggregationRepository.ActiveDayRow::activeDays,
                        Integer::sum));
    }

    /**
     * Returns {@code true} when any projection row produced by this resolver should be
     * flagged {@code is_incomplete_data = true} — i.e., the roster data source is absent.
     */
    boolean isIncompleteData() {
        return !ROSTER_DATA_AVAILABLE;
    }
}
