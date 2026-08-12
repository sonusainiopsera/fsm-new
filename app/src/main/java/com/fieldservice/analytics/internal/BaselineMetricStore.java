package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads captured baseline values from {@code baseline_metric} (WO-162).
 *
 * <p>When no row exists for a (metric_key, segment_key, window_key), the caller
 * receives {@code null} and must render target_attainment as BASELINE_PENDING (BR-30).
 * No default, invented, or vendor-published target is ever substituted.
 */
@Component
class BaselineMetricStore {

    private static final Logger log = LoggerFactory.getLogger(BaselineMetricStore.class);

    private final JdbcTemplate jdbcTemplate;

    BaselineMetricStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the baseline value for a specific (metric, segment, window) combination,
     * or {@code null} if no row has been captured yet.
     */
    @Nullable
    BigDecimal findBaseline(String metricKey, String segmentKey, String windowKey) {
        try {
            List<BigDecimal> rows = jdbcTemplate.query(
                    "SELECT baseline_value FROM baseline_metric " +
                    "WHERE metric_key = ? AND segment_key = ? AND window_key = ?",
                    (rs, i) -> rs.getBigDecimal("baseline_value"),
                    metricKey, segmentKey, windowKey);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception ex) {
            log.warn("baseline_metric.read.error: metricKey={} segment={} window={} — {}",
                    metricKey, segmentKey, windowKey, ex.getMessage());
            return null;
        }
    }

    /**
     * Returns a map of segmentKey → baseline_value for all segments of a metric+window.
     * Useful for bulk lookup during a single recomputation run.
     */
    Map<String, BigDecimal> findAllBaselines(String metricKey, String windowKey) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT segment_key, baseline_value FROM baseline_metric " +
                    "WHERE metric_key = ? AND window_key = ?",
                    metricKey, windowKey);
            return rows.stream().collect(Collectors.toMap(
                    r -> (String) r.get("segment_key"),
                    r -> (BigDecimal) r.get("baseline_value")));
        } catch (Exception ex) {
            log.warn("baseline_metric.read_all.error: metricKey={} window={} — {}",
                    metricKey, windowKey, ex.getMessage());
            return Map.of();
        }
    }
}
