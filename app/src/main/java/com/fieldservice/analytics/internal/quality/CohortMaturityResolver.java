package com.fieldservice.analytics.internal.quality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Promotes work orders from the PROVISIONAL cohort to the MATURED cohort.
 *
 * <h3>Promotion rule</h3>
 * A closure projection row becomes MATURED at exactly {@code matured_at = closed_at + 30 days}.
 * Promotion is an idempotent conditional {@code UPDATE ... WHERE maturity = 'PROVISIONAL' AND matured_at <= :now}
 * so replaying the sweep is safe even if called multiple times in the same second.
 *
 * <h3>Clock injection</h3>
 * The clock is injected to allow deterministic boundary testing — tests advance the clock
 * to exactly the 30-day instant and assert promotion without sleeping.
 */
@Component
public class CohortMaturityResolver {

    private static final Logger log = LoggerFactory.getLogger(CohortMaturityResolver.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    CohortMaturityResolver(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Promotes all PROVISIONAL rows whose {@code matured_at} is on or before {@code now}.
     *
     * @return the number of rows promoted in this sweep
     */
    @Transactional
    int promoteMaturedRows() {
        Instant now = clock.instant();
        int promoted = jdbcTemplate.update(
                "UPDATE analytics_closure_projection " +
                "SET maturity = 'MATURED', updated_at = ? " +
                "WHERE maturity = 'PROVISIONAL' AND matured_at <= ?",
                now, now);
        if (promoted > 0) {
            log.info("quality.maturation.promoted: count={} asOf={}", promoted, now);
        }
        return promoted;
    }

    /**
     * Determines the maturity of a single work order's closure projection at the current clock time.
     * Used for unit testing and spot-checks; production flow uses {@link #promoteMaturedRows()}.
     *
     * @param closedAt the instant the work order was closed
     * @return "MATURED" if {@code closedAt + 30 days <= now}, otherwise "PROVISIONAL"
     */
    String resolveMaturity(Instant closedAt) {
        Instant maturedAt = closedAt.plus(java.time.Duration.ofDays(30));
        return !clock.instant().isBefore(maturedAt) ? "MATURED" : "PROVISIONAL";
    }
}
