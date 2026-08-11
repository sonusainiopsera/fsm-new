package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Writes immutable daily trend points to {@code kpi_trend_point} (WO-165).
 *
 * <p>Each call attempts to insert a row for today's date. If a row already exists for
 * (metric_key, segment_key, bucket_date), the INSERT is silently skipped so the historical
 * record is never overwritten — enforcing the AC-5 immutability requirement.
 */
@Component
class TrendPointWriter {

    private static final Logger log = LoggerFactory.getLogger(TrendPointWriter.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    TrendPointWriter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Writes a trend point for today (UTC) if one does not already exist.
     *
     * @param metricKey  the metric key
     * @param segmentKey the segment key (e.g. "ALL", "ON_HOLD")
     * @param value      the numeric value to record
     * @param sampleCount the number of data points contributing to this value
     */
    void writeTodayIfAbsent(String metricKey, String segmentKey, BigDecimal value, int sampleCount) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            int rows = jdbcTemplate.update(
                    "INSERT INTO kpi_trend_point (id, metric_key, segment_key, bucket_date, value, sample_count, written_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (metric_key, segment_key, bucket_date) DO NOTHING",
                    UUID.randomUUID(), metricKey, segmentKey,
                    java.sql.Date.valueOf(today), value, sampleCount, clock.instant());
            if (rows > 0) {
                log.debug("trend_point.written: metricKey={} segment={} date={} value={}",
                        metricKey, segmentKey, today, value);
            }
        } catch (DataIntegrityViolationException ex) {
            // Race condition — another node already wrote today's point
            log.debug("trend_point.race_skip: metricKey={} segment={} date={}", metricKey, segmentKey, today);
        }
    }
}
