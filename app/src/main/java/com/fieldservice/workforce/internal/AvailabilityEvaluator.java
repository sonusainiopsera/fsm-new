package com.fieldservice.workforce.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Pure availability evaluator — no Spring or JPA dependency.
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li>A technician with no availability windows is always <em>unavailable</em>
 *       (fail-safe: never default to available).</li>
 *   <li>A window covers an interval [from, to] when:
 *       <ul>
 *         <li>{@code from} and {@code to} are on the same calendar day in the
 *             technician's timezone;</li>
 *         <li>the window is active on that date ({@code effectiveFrom <= date <= effectiveTo});</li>
 *         <li>the window's {@code startTime <= from_time} and {@code endTime >= to_time};</li>
 *         <li>the window's {@code dayOfWeek} matches the ISO day-of-week of the date.</li>
 *       </ul>
 *   </li>
 *   <li>An absence suppresses availability when it overlaps the interval
 *       ({@code absence.startsAt < to && absence.endsAt > from}).</li>
 *   <li>Cross-midnight intervals are <em>not supported</em>; the caller must ensure
 *       {@code from} and {@code to} fall on the same local calendar day.</li>
 * </ol>
 *
 * <p>This class is intentionally framework-free so it can be unit-tested exhaustively
 * without a Spring context or database.
 */
public final class AvailabilityEvaluator {

    private AvailabilityEvaluator() {}

    /**
     * Returns {@code true} when the technician is available for the entire interval
     * [{@code from}, {@code to}], i.e. a window covers the interval AND no absence
     * overlaps it.
     *
     * @param windows   all availability windows for the technician (may be empty)
     * @param absences  all absences for the technician (may be empty)
     * @param timezone  technician's local timezone for window evaluation
     * @param from      interval start (inclusive)
     * @param to        interval end (inclusive)
     * @return {@code true} iff available
     */
    public static boolean isAvailableBetween(
            List<AvailabilityWindow> windows,
            List<Absence> absences,
            ZoneId timezone,
            Instant from,
            Instant to) {

        if (windows == null || windows.isEmpty()) {
            return false;
        }

        // Check no absence overlaps [from, to)
        for (Absence absence : absences) {
            if (absence.startsAt().isBefore(to) && absence.endsAt().isAfter(from)) {
                return false;
            }
        }

        // Convert instants to the technician's timezone
        LocalDateTime localFrom = LocalDateTime.ofInstant(from, timezone);
        LocalDateTime localTo   = LocalDateTime.ofInstant(to,   timezone);

        // Cross-midnight check: from and to must be on the same calendar day
        LocalDate dateFrom = localFrom.toLocalDate();
        LocalDate dateTo   = localTo.toLocalDate();
        if (!dateFrom.equals(dateTo)) {
            return false;
        }

        LocalDate    date       = dateFrom;
        DayOfWeek    dow        = date.getDayOfWeek();         // ISO: MONDAY=1..SUNDAY=7
        int          isoDow     = dow.getValue();
        LocalTime    timeFrom   = localFrom.toLocalTime();
        LocalTime    timeTo     = localTo.toLocalTime();

        for (AvailabilityWindow w : windows) {
            if (w.dayOfWeek() != isoDow) continue;
            if (w.effectiveFrom().isAfter(date)) continue;
            if (w.effectiveTo() != null && w.effectiveTo().isBefore(date)) continue;
            if (w.startTime().isAfter(timeFrom)) continue;
            if (w.endTime().isBefore(timeTo)) continue;
            // This window covers the interval
            return true;
        }

        return false;
    }

    // ---- Value objects used by the evaluator (no JPA annotations) ----------

    /**
     * Immutable representation of a recurring availability window.
     * {@code dayOfWeek}: 1=Monday .. 7=Sunday (ISO-8601).
     */
    public record AvailabilityWindow(
            int       dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {}

    /**
     * Immutable representation of a dated absence.
     */
    public record Absence(
            Instant startsAt,
            Instant endsAt
    ) {}
}
