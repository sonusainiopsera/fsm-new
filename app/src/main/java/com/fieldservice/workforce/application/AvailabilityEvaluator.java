package com.fieldservice.workforce.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Pure-function availability evaluator — no Spring or JPA dependencies.
 *
 * <p>Answers whether a technician is available for an entire requested instant interval
 * by checking the interval against recurring weekly working windows and absence records.
 *
 * <p>Fail-safe defaults:
 * <ul>
 *   <li>A technician with no windows is always <em>unavailable</em> (never fails open).</li>
 *   <li>A window spanning midnight is represented as two windows in the database; this
 *       evaluator treats end_time as exclusive same-day.</li>
 * </ul>
 *
 * <p>Timezone handling: {@code from}/{@code to} instants are converted to the technician's
 * {@code timezone} before comparing with window times. DST transitions are handled by
 * {@link ZonedDateTime} arithmetic.
 */
public class AvailabilityEvaluator {

    /** Value object representing one recurring weekly window. */
    public record WindowDto(
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            LocalDate effectiveFrom,
            LocalDate effectiveTo   // null = open-ended
    ) {}

    /** Value object representing one absence period. */
    public record AbsenceDto(
            Instant startsAt,
            Instant endsAt
    ) {}

    /**
     * Returns {@code true} only when the entire interval {@code [from, to)} is covered by
     * at least one working window per calendar day (in the technician's timezone) and no
     * absence overlaps the interval.
     *
     * @param windows   recurring working windows; must not be null
     * @param absences  absence records; must not be null
     * @param timezone  technician's local timezone for window evaluation
     * @param from      inclusive start of requested interval
     * @param to        exclusive end of requested interval (must be after {@code from})
     */
    public boolean isAvailableBetween(
            List<WindowDto> windows,
            List<AbsenceDto> absences,
            ZoneId timezone,
            Instant from,
            Instant to) {

        if (windows == null || windows.isEmpty()) {
            return false;
        }
        if (!to.isAfter(from)) {
            return false;
        }

        for (AbsenceDto absence : absences) {
            if (overlaps(absence.startsAt(), absence.endsAt(), from, to)) {
                return false;
            }
        }

        ZonedDateTime fromZdt = from.atZone(timezone);
        ZonedDateTime toZdt   = to.atZone(timezone);

        return intervalCoveredByWindows(windows, fromZdt, toZdt);
    }

    private boolean overlaps(Instant absStart, Instant absEnd, Instant from, Instant to) {
        return absStart.isBefore(to) && absEnd.isAfter(from);
    }

    private boolean intervalCoveredByWindows(List<WindowDto> windows,
                                              ZonedDateTime from, ZonedDateTime to) {
        ZoneId zone = from.getZone();
        LocalDate fromDate = from.toLocalDate();
        LocalDate toDate   = to.toLocalDate();

        LocalDate current = fromDate;
        while (!current.isAfter(toDate)) {
            DayOfWeek dow    = current.getDayOfWeek();
            // The portion of this calendar day that falls within [from, to)
            LocalTime dayStart = current.equals(fromDate) ? from.toLocalTime() : LocalTime.MIDNIGHT;
            LocalTime dayEnd;
            if (current.equals(toDate)) {
                dayEnd = to.toLocalTime();
                if (dayEnd.equals(LocalTime.MIDNIGHT) && current.equals(toDate) && !current.equals(fromDate)) {
                    // to is exactly midnight — the previous day is the last covered day
                    break;
                }
            } else {
                dayEnd = LocalTime.MAX;
            }

            if (!dayHasWindow(windows, dow, current, dayStart, dayEnd)) {
                return false;
            }
            current = current.plusDays(1);
        }
        return true;
    }

    private boolean dayHasWindow(List<WindowDto> windows, DayOfWeek dow, LocalDate date,
                                  LocalTime requiredStart, LocalTime requiredEnd) {
        for (WindowDto w : windows) {
            if (w.dayOfWeek() == dow
                    && isEffective(w, date)
                    && !w.startTime().isAfter(requiredStart)
                    && !w.endTime().isBefore(requiredEnd)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEffective(WindowDto window, LocalDate date) {
        return !date.isBefore(window.effectiveFrom())
                && (window.effectiveTo() == null || !date.isAfter(window.effectiveTo()));
    }
}
