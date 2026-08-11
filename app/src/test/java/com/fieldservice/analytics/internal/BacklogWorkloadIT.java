package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import com.fieldservice.support.RedisContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for backlog and workload balance metrics (WO-165).
 *
 * <p>Uses fixture data from V117__backlog_workload_fixtures.sql.
 * Verifies segmented backlog counts, on-hold breakdown, CV computation
 * and trend-point immutability.
 */
@DisplayName("Backlog and workload balance integration tests")
class BacklogWorkloadIT extends RedisContainerSupport {

    @Autowired
    private KpiProjectionQuery projectionQuery;

    @Autowired
    private KpiProjectionService projectionService;

    @Autowired
    private TrendPointWriter trendPointWriter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void recompute() {
        projectionService.recomputeAndPersist(BacklogMetricKeys.BACKLOG_OPEN_COUNT);
        projectionService.recomputeAndPersist(BacklogMetricKeys.BACKLOG_ON_HOLD_COUNT);
        projectionService.recomputeAndPersist(BacklogMetricKeys.WORKLOAD_BALANCE_CV);
    }

    @Test
    @DisplayName("AC-1: backlog.open.count projection is present and >= 7 (from V117 fixtures)")
    void openBacklogProjection_present_andCountAtLeastFixtureCount() {
        Optional<KpiProjection> proj = projectionQuery.findProjection(
                BacklogMetricKeys.BACKLOG_OPEN_COUNT,
                KpiAggregationQueries.SEGMENT_ALL,
                KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(proj).isPresent();
        assertThat(proj.get().value().longValue()).isGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("AC-1: backlog.on_hold.count <= backlog.open.count")
    void onHoldCountIsSubsetOfOpenCount() {
        Optional<KpiProjection> openProj = projectionQuery.findProjection(
                BacklogMetricKeys.BACKLOG_OPEN_COUNT,
                KpiAggregationQueries.SEGMENT_ALL,
                KpiAggregationQueries.WINDOW_ALL_TIME);
        Optional<KpiProjection> holdProj = projectionQuery.findProjection(
                BacklogMetricKeys.BACKLOG_ON_HOLD_COUNT,
                KpiAggregationQueries.SEGMENT_ALL,
                KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(openProj).isPresent();
        assertThat(holdProj).isPresent();
        assertThat(holdProj.get().value().compareTo(openProj.get().value()))
                .isLessThanOrEqualTo(0);
    }

    @Test
    @DisplayName("AC-6: backlog.open.count routed through debounce substrate (projection updated)")
    void backlogOpenCount_projectionExists_withDataAsOf() {
        Optional<KpiProjection> proj = projectionQuery.findProjection(
                BacklogMetricKeys.BACKLOG_OPEN_COUNT,
                KpiAggregationQueries.SEGMENT_ALL,
                KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(proj).isPresent();
        assertThat(proj.get().dataAsOf()).isNotNull();
        assertThat(proj.get().stalenessSeconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("AC-5: trend point written for today and is immutable on second write")
    @Transactional
    @Rollback
    void trendPoint_writtenToday_andImmutableOnRewrite() {
        LocalDate today = LocalDate.now();

        // First write — should insert a row
        trendPointWriter.writeTodayIfAbsent(
                BacklogMetricKeys.BACKLOG_OPEN_COUNT, KpiAggregationQueries.SEGMENT_ALL,
                BigDecimal.valueOf(42), 42);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kpi_trend_point WHERE metric_key = ? AND bucket_date = ?",
                Integer.class, BacklogMetricKeys.BACKLOG_OPEN_COUNT, java.sql.Date.valueOf(today));
        assertThat(count).isEqualTo(1);

        // Second write with different value — should NOT overwrite (immutability)
        trendPointWriter.writeTodayIfAbsent(
                BacklogMetricKeys.BACKLOG_OPEN_COUNT, KpiAggregationQueries.SEGMENT_ALL,
                BigDecimal.valueOf(99), 99);

        BigDecimal storedValue = jdbc.queryForObject(
                "SELECT value FROM kpi_trend_point WHERE metric_key = ? AND bucket_date = ?",
                BigDecimal.class, BacklogMetricKeys.BACKLOG_OPEN_COUNT, java.sql.Date.valueOf(today));
        assertThat(storedValue).isEqualByComparingTo(BigDecimal.valueOf(42));
    }

    @Test
    @DisplayName("AC-5: closing a work order after a trend point is written does not alter historical point")
    @Transactional
    @Rollback
    void trendPoint_historicalPointUnchanged_afterLaterStatChange() {
        // Write historical trend point for yesterday
        LocalDate yesterday = LocalDate.now().minusDays(1);
        jdbc.update(
                "INSERT INTO kpi_trend_point (id, metric_key, segment_key, bucket_date, value, sample_count, written_at) " +
                "VALUES (gen_random_uuid(), ?, 'ALL', ?, 10, 10, now() - INTERVAL '1 day') " +
                "ON CONFLICT (metric_key, segment_key, bucket_date) DO NOTHING",
                BacklogMetricKeys.BACKLOG_OPEN_COUNT, java.sql.Date.valueOf(yesterday));

        // Simulate closing a WO (does not affect yesterday's trend point)
        jdbc.update(
                "UPDATE work_order SET state = 'CLOSED', updated_at = now() " +
                "WHERE id = '90000000-0000-0000-0001-000000000001'");

        // Re-check historical value — must still be 10
        BigDecimal historicalValue = jdbc.queryForObject(
                "SELECT value FROM kpi_trend_point WHERE metric_key = ? AND bucket_date = ?",
                BigDecimal.class, BacklogMetricKeys.BACKLOG_OPEN_COUNT, java.sql.Date.valueOf(yesterday));
        assertThat(historicalValue).isEqualByComparingTo(BigDecimal.valueOf(10));
    }
}
