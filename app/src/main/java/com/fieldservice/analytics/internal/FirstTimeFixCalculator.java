package com.fieldservice.analytics.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Computes first-time fix rate metrics from the {@code analytics_closure_projection} table.
 *
 * <p>Formula: matured_ftf_rate = FTF_matured / total_matured_classifiable.
 * Work orders with {@code fault_key IS NULL} (UNCLASSIFIABLE) are excluded from both
 * numerator and denominator. An empty cohort returns {@code null} (no-data), not 0%.
 */
@Component
class FirstTimeFixCalculator {

    private static final Logger log = LoggerFactory.getLogger(FirstTimeFixCalculator.class);

    private final JdbcTemplate replicaJdbcTemplate;

    FirstTimeFixCalculator(@Qualifier("replicaJdbcTemplate") JdbcTemplate replicaJdbcTemplate) {
        this.replicaJdbcTemplate = replicaJdbcTemplate;
    }

    /** Matured FTF rate; {@code null} if the matured classifiable cohort is empty. */
    @Nullable
    KpiAggregationQueries.AggregateResult queryMaturedRate() {
        return queryRate("MATURED");
    }

    /** Provisional FTF rate (BR-30: must always be labelled PROVISIONAL by callers). */
    @Nullable
    KpiAggregationQueries.AggregateResult queryProvisionalRate() {
        return queryRate("PROVISIONAL");
    }

    /** Total count of repeat visit links. */
    KpiAggregationQueries.AggregateResult queryRepeatVisitCount() {
        Long count = replicaJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repeat_visit_link", Long.class);
        long c = count == null ? 0L : count;
        return new KpiAggregationQueries.AggregateResult(
                BigDecimal.valueOf(c), BigDecimal.valueOf(c), BigDecimal.ONE, (int) c);
    }

    /** Count of UNCLASSIFIABLE closure projection rows (fault_key IS NULL). */
    KpiAggregationQueries.AggregateResult queryUnclassifiableCount() {
        Long count = replicaJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_closure_projection WHERE fault_key IS NULL",
                Long.class);
        long c = count == null ? 0L : count;
        return new KpiAggregationQueries.AggregateResult(
                BigDecimal.valueOf(c), BigDecimal.valueOf(c), BigDecimal.ONE, (int) c);
    }

    @Nullable
    private KpiAggregationQueries.AggregateResult queryRate(String maturity) {
        try {
            Map<String, Object> row = replicaJdbcTemplate.queryForMap(
                    "SELECT " +
                    "  COUNT(*) FILTER (WHERE is_first_time_fix = true) AS ftf_count, " +
                    "  COUNT(*) AS total " +
                    "FROM analytics_closure_projection " +
                    "WHERE maturity = ? AND fault_key IS NOT NULL",
                    maturity);

            long ftf   = toLong(row.get("ftf_count"));
            long total = toLong(row.get("total"));

            if (total == 0) return null;

            BigDecimal rate = BigDecimal.valueOf(ftf)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            return new KpiAggregationQueries.AggregateResult(
                    rate, BigDecimal.valueOf(ftf), BigDecimal.valueOf(total), (int) total);
        } catch (Exception ex) {
            log.error("quality.ftf.query_rate.error: maturity={} — {}", maturity, ex.getMessage());
            throw ex;
        }
    }

    private static long toLong(@Nullable Object val) {
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }
}
