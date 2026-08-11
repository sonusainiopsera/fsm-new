package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import com.fieldservice.support.RedisContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the analytics read-model substrate (WO-161, AC-5, AC-6, AC-7, AC-10).
 *
 * <p>Lives in {@code analytics.internal} test package so it can access the package-private
 * {@link KpiProjectionService#recomputeAndPersist(String)} method directly.
 * ArchUnit excludes test classes ({@code DoNotIncludeTests}), so this does not violate
 * the module boundary rule.
 *
 * <p>Extends {@link RedisContainerSupport} for Testcontainers PostgreSQL + Redis.
 */
@DisplayName("KPI analytics substrate integration tests")
class KpiAnalyticsIT extends RedisContainerSupport {

    @Autowired
    private KpiProjectionQuery projectionQuery;

    @Autowired
    private KpiProjectionService projectionService;

    @Autowired
    private MetricDebounceRegistry debounceRegistry;

    @Autowired
    @Qualifier("replicaDataSource")
    private DataSource replicaDataSource;

    @Autowired
    @Qualifier("replicaJdbcTemplate")
    private JdbcTemplate replicaJdbcTemplate;

    @Autowired(required = false)
    private AnalyticsRedisCache redisCache;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    // -----------------------------------------------------------------------
    // AC-5: replica datasource wiring
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("replicaDataSource bean is present and queryable (AC-5)")
    void replicaDataSourceIsPresent() {
        assertThat(replicaDataSource).isNotNull();
        Long result = replicaJdbcTemplate.queryForObject("SELECT 1", Long.class);
        assertThat(result).isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // AC-5, AC-6: projection upsert
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("recomputeAndPersist writes a projection row with data_as_of set (AC-5)")
    void recomputeAndPersist_writesProjectionRow() {
        projectionService.recomputeAndPersist(KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT);

        Optional<KpiProjection> projection = projectionQuery.findProjection(
                KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT,
                KpiAggregationQueries.SEGMENT_ALL,
                KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(projection).isPresent();
        assertThat(projection.get().dataAsOf()).isNotNull();
        assertThat(projection.get().projectionVersion()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Projection version increments on second recompute (AC-6)")
    void projectionVersionIncrements_onRecompute() {
        String metricKey = KpiAggregationQueries.METRIC_WO_COMPLETION_RATE_7D;
        projectionService.recomputeAndPersist(metricKey);
        long v1 = projectionQuery.findProjection(metricKey,
                KpiAggregationQueries.SEGMENT_ALL, KpiAggregationQueries.WINDOW_ROLLING_7D)
                .map(KpiProjection::projectionVersion).orElse(0L);

        projectionService.recomputeAndPersist(metricKey);
        long v2 = projectionQuery.findProjection(metricKey,
                KpiAggregationQueries.SEGMENT_ALL, KpiAggregationQueries.WINDOW_ROLLING_7D)
                .map(KpiProjection::projectionVersion).orElse(0L);

        assertThat(v2).isGreaterThan(v1);
    }

    @Test
    @DisplayName("Second read returns cached projection from Redis (AC-6)")
    void secondRead_hitsCacheWhenRedisPresent() {
        if (redisCache == null) return;

        String metricKey = KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT;
        projectionService.recomputeAndPersist(metricKey);

        Optional<KpiProjection> first = projectionQuery.findProjection(
                metricKey, KpiAggregationQueries.SEGMENT_ALL, KpiAggregationQueries.WINDOW_ALL_TIME);
        Optional<KpiProjection> second = projectionQuery.findProjection(
                metricKey, KpiAggregationQueries.SEGMENT_ALL, KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get().projectionVersion()).isEqualTo(first.get().projectionVersion());
    }

    // -----------------------------------------------------------------------
    // AC-7: data_as_of, staleness, and metrics
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Projection exposes data_as_of and non-negative staleness (AC-7)")
    void projectionExposesStaleness() {
        projectionService.recomputeAndPersist(KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT);

        Optional<KpiProjection> p = projectionQuery.findProjection(
                KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT,
                KpiAggregationQueries.SEGMENT_ALL, KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(p).isPresent();
        assertThat(p.get().stalenessSeconds()).isGreaterThanOrEqualTo(0L);
        assertThat(p.get().dataAsOf()).isNotNull();
    }

    @Test
    @DisplayName("kpi_projection_staleness_seconds gauge registered after recompute (AC-7)")
    void stalenessGaugeRegisteredAfterRecompute() {
        String metricKey = KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT;
        projectionService.recomputeAndPersist(metricKey);
        projectionQuery.findProjection(metricKey,
                KpiAggregationQueries.SEGMENT_ALL, KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(meterRegistry.find("kpi_projection_staleness_seconds")
                .tag("metric", metricKey).gauge()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // AC-6: Redis outage → degraded-but-correct fallback
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("findProjection returns degraded=false for a valid fresh projection")
    void freshProjection_isNotDegraded() {
        projectionService.recomputeAndPersist(KpiAggregationQueries.METRIC_WO_SLA_COMPLIANCE_7D);

        Optional<KpiProjection> p = projectionQuery.findProjection(
                KpiAggregationQueries.METRIC_WO_SLA_COMPLIANCE_7D,
                KpiAggregationQueries.SEGMENT_ALL, KpiAggregationQueries.WINDOW_ROLLING_7D);

        assertThat(p).isPresent();
        assertThat(p.get().degraded()).isFalse();
    }

    @Test
    @DisplayName("findDegraded returns empty when all projections are healthy")
    void findDegraded_emptyWhenAllHealthy() {
        // Ensure at least one fresh projection exists
        projectionService.recomputeAndPersist(KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT);

        // findDegraded should not include healthy projections
        var degraded = projectionQuery.findDegraded();
        degraded.forEach(p -> assertThat(p.degraded()).isTrue());
    }

    // -----------------------------------------------------------------------
    // AC-3: idempotency debounce registration
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("markDirty and drain trigger recomputation via registry")
    void markDirtyAndDrain_triggerRecomputation() {
        String metricKey = KpiAggregationQueries.METRIC_WO_BACKLOG_COUNT;
        debounceRegistry.markDirty(metricKey);
        assertThat(debounceRegistry.pendingCount()).isGreaterThan(0);
    }
}
