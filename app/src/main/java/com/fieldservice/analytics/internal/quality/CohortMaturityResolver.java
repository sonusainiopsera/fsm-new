package com.fieldservice.analytics.internal.quality;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Resolves cohort maturity for a closed work order using an injected clock.
 *
 * <p>Maturity rules:
 * <ul>
 *   <li>PROVISIONAL — the 30-day observation window has not yet elapsed.</li>
 *   <li>MATURED     — 30 or more days have elapsed since closure.</li>
 * </ul>
 *
 * <p>Boundary: closed exactly 30 days ago → MATURED (not PROVISIONAL).
 * Repeat-visit window: strictly less than 30 days between closures; at exactly 30 days
 * the later visit falls outside the window and does not break first-time fix.
 */
@Component
public class CohortMaturityResolver {

    static final int WINDOW_DAYS = 30;
    static final Duration WINDOW = Duration.ofDays(WINDOW_DAYS);

    private final Clock clock;

    public CohortMaturityResolver(Clock clock) {
        this.clock = clock;
    }

    /** Returns "PROVISIONAL" or "MATURED" for the given closure timestamp. */
    public String resolve(Instant closedAt) {
        Instant now = clock.instant();
        return now.isBefore(maturedAt(closedAt)) ? "PROVISIONAL" : "MATURED";
    }

    /** Returns the instant at which a work order closed at {@code closedAt} becomes MATURED. */
    public Instant maturedAt(Instant closedAt) {
        return closedAt.plus(WINDOW_DAYS, ChronoUnit.DAYS);
    }

    /**
     * Returns true when the later closure is a repeat visit to the same asset + fault.
     * Boundary: strictly less than 30 days — exactly 30 days is NOT a repeat.
     */
    public boolean isWithinRepeatWindow(Instant earlierClosedAt, Instant laterClosedAt) {
        if (!laterClosedAt.isAfter(earlierClosedAt)) {
            return false;
        }
        Duration gap = Duration.between(earlierClosedAt, laterClosedAt);
        return gap.compareTo(WINDOW) < 0;
    }

    /** Whole-day count between closures, for storage in {@code repeat_visit_link.days_between}. */
    public int daysBetween(Instant earlier, Instant later) {
        return (int) ChronoUnit.DAYS.between(earlier, later);
    }
}
