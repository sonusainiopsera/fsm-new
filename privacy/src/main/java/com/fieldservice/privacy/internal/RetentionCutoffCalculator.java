package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.RetentionPeriodUnit;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Computes retention cut-off instants from a period value, unit and reference clock.
 *
 * <p>MONTHS and YEARS use calendar arithmetic (via {@link ZonedDateTime}) rather than
 * fixed-length days to preserve month/year boundary semantics across daylight-saving
 * transitions and leap years.  DAYS uses simple subtraction, which is DST-neutral.
 */
@Component
class RetentionCutoffCalculator {

    private final Clock  clock;
    private final ZoneId zone;

    RetentionCutoffCalculator(Clock clock,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${app.privacy.purge.zone:UTC}") String zoneId) {
        this.clock = clock;
        this.zone  = ZoneId.of(zoneId);
    }

    /**
     * Returns the cut-off instant: rows with an anchor timestamp strictly before
     * this instant are eligible for disposal.
     *
     * @param periodValue the retention period length (must be > 0)
     * @param unit        the period unit
     * @return the cut-off instant
     */
    Instant computeCutoff(int periodValue, RetentionPeriodUnit unit) {
        Instant now = clock.instant();
        return switch (unit) {
            case DAYS   -> now.minus(periodValue, ChronoUnit.DAYS);
            case MONTHS -> ZonedDateTime.ofInstant(now, zone)
                                        .minus(Period.ofMonths(periodValue))
                                        .toInstant();
            case YEARS  -> ZonedDateTime.ofInstant(now, zone)
                                        .minus(Period.ofYears(periodValue))
                                        .toInstant();
        };
    }
}
