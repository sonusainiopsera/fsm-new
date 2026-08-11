package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import com.fieldservice.analytics.internal.quality.CohortMaturityResolver;
import com.fieldservice.analytics.internal.quality.RepeatVisitLinker;
import com.fieldservice.support.RedisContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for first-time fix quality (WO-164).
 *
 * <p>Uses fixture data from V115__quality_fixtures.sql seeded via Flyway test migrations.
 * Exercises the full pipeline: RepeatVisitLinker → CohortMaturityResolver →
 * KpiProjectionService → KpiProjectionQuery.
 */
@DisplayName("FirstTimeFix integration tests")
class FirstTimeFixIT extends RedisContainerSupport {

    @Autowired
    private KpiProjectionQuery projectionQuery;

    @Autowired
    private KpiProjectionService projectionService;

    @Autowired
    private RepeatVisitLinker repeatVisitLinker;

    @Autowired
    private CohortMaturityResolver cohortMaturityResolver;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void recomputeProjections() {
        // Force projection recomputation from fixture data
        projectionService.recomputeAndPersist(QualityMetricKeys.FTF_MATURED);
        projectionService.recomputeAndPersist(QualityMetricKeys.FTF_PROVISIONAL);
        projectionService.recomputeAndPersist(QualityMetricKeys.REPEAT_VISIT_COUNT);
        projectionService.recomputeAndPersist(QualityMetricKeys.UNCLASSIFIABLE_COUNT);
    }

    @Test
    @DisplayName("AC-1: matured FTF rate projection is present and has value <= 1")
    void maturedProjectionPresent_rateWithinBounds() {
        Optional<KpiProjection> proj = projectionQuery.findProjection(
                QualityMetricKeys.FTF_MATURED,
                KpiAggregationQueries.SEGMENT_ALL,
                KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(proj).isPresent();
        assertThat(proj.get().value()).isNotNull();
        assertThat(proj.get().value().doubleValue())
                .isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("BR-30: provisional projection stored with PROVISIONAL segment key")
    void provisionalProjection_storedWithProvisionalSegment() {
        Optional<KpiProjection> proj = projectionQuery.findProjection(
                QualityMetricKeys.FTF_PROVISIONAL,
                QualityMetricKeys.SEGMENT_PROVISIONAL,
                KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(proj).isPresent();
        assertThat(proj.get().segmentKey()).isEqualTo("PROVISIONAL");
    }

    @Test
    @DisplayName("AC-5: unclassifiable count >= 2 (from F scenario in fixtures)")
    void unclassifiableCount_atLeastTwo() {
        Optional<KpiProjection> proj = projectionQuery.findProjection(
                QualityMetricKeys.UNCLASSIFIABLE_COUNT,
                KpiAggregationQueries.SEGMENT_ALL,
                KpiAggregationQueries.WINDOW_ALL_TIME);

        assertThat(proj).isPresent();
        assertThat(proj.get().value().longValue()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("AC-3: repeat visit count includes linked pairs from fixtures")
    void repeatVisitCount_includesLinkedPairs() {
        Optional<KpiProjection> proj = projectionQuery.findProjection(
                QualityMetricKeys.REPEAT_VISIT_COUNT,
                KpiAggregationQueries.SEGMENT_ALL,
                KpiAggregationQueries.WINDOW_ALL_TIME);

        // B-scenario (1d), C-scenario (29d), E-chain (2 links) = at least 4 links in fixtures
        assertThat(proj).isPresent();
        assertThat(proj.get().value().longValue()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("AC-2: linker creates repeat_visit_link for new closure within 29 days")
    @Transactional
    @Rollback
    void linker_createsRepeatVisitLink_for29DayClosure() {
        UUID assetId = UUID.fromString("20000000-0000-0000-0000-000000000001");

        // Seed an earlier closure directly
        UUID earlierWoId = UUID.randomUUID();
        Instant earlierClosed = Instant.now().minus(Duration.ofDays(10));
        jdbc.update(
                "INSERT INTO work_order (id, tenant_id, title, state, priority, asset_id, fault_code, created_at, updated_at, version) " +
                "VALUES (?, '00000000-0000-0000-0000-000000000001', 'IT earlier', 'CLOSED', 'LOW', ?, 'IT-FC', ?, ?, 0)",
                earlierWoId, assetId, earlierClosed, earlierClosed);
        jdbc.update(
                "INSERT INTO analytics_closure_projection (id, work_order_id, asset_id, fault_key, is_first_time_fix, maturity, closed_at, matured_at, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'IT-FC', TRUE, 'PROVISIONAL', ?, ?, now(), now())",
                earlierWoId, assetId, earlierClosed, earlierClosed.plus(Duration.ofDays(30)));

        // Process a new closure 5 days later
        UUID laterWoId = UUID.randomUUID();
        Instant laterClosed = earlierClosed.plus(Duration.ofDays(5));
        jdbc.update(
                "INSERT INTO work_order (id, tenant_id, title, state, priority, asset_id, fault_code, created_at, updated_at, version) " +
                "VALUES (?, '00000000-0000-0000-0000-000000000001', 'IT later', 'CLOSED', 'LOW', ?, 'IT-FC', ?, ?, 0)",
                laterWoId, assetId, laterClosed, laterClosed);

        repeatVisitLinker.processClosureEvent(laterWoId, laterClosed);

        Integer linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repeat_visit_link WHERE earlier_work_order_id = ? AND later_work_order_id = ?",
                Integer.class, earlierWoId, laterWoId);
        assertThat(linkCount).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-4: PROVISIONAL→MATURED promotion works when matured_at elapses")
    @Transactional
    @Rollback
    void maturationSweep_promotesProvisionalRow() {
        // Seed a row with matured_at in the past
        UUID woId = UUID.randomUUID();
        Instant pastClosed = Instant.now().minus(Duration.ofDays(35));
        jdbc.update(
                "INSERT INTO work_order (id, tenant_id, title, state, priority, asset_id, fault_code, created_at, updated_at, version) " +
                "VALUES (?, '00000000-0000-0000-0000-000000000001', 'IT prov', 'CLOSED', 'LOW', '20000000-0000-0000-0000-000000000001', 'PROV-FC', ?, ?, 0)",
                woId, pastClosed, pastClosed);
        jdbc.update(
                "INSERT INTO analytics_closure_projection (id, work_order_id, asset_id, fault_key, is_first_time_fix, maturity, closed_at, matured_at, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, '20000000-0000-0000-0000-000000000001', 'PROV-FC', TRUE, 'PROVISIONAL', ?, ?, now(), now())",
                woId, pastClosed, pastClosed.plus(Duration.ofDays(30)));

        int promoted = cohortMaturityResolver.promoteMaturedRows();

        assertThat(promoted).isGreaterThanOrEqualTo(1);

        String maturity = jdbc.queryForObject(
                "SELECT maturity FROM analytics_closure_projection WHERE work_order_id = ?",
                String.class, woId);
        assertThat(maturity).isEqualTo("MATURED");
    }

    @Test
    @DisplayName("Idempotency: replaying processClosureEvent creates no duplicate links")
    @Transactional
    @Rollback
    void linker_idempotent_onReplay() {
        UUID assetId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        UUID earlierWoId = UUID.randomUUID();
        Instant earlierClosed = Instant.now().minus(Duration.ofDays(10));

        jdbc.update(
                "INSERT INTO work_order (id, tenant_id, title, state, priority, asset_id, fault_code, created_at, updated_at, version) " +
                "VALUES (?, '00000000-0000-0000-0000-000000000001', 'Idem earlier', 'CLOSED', 'LOW', ?, 'IDEM-FC', ?, ?, 0)",
                earlierWoId, assetId, earlierClosed, earlierClosed);
        jdbc.update(
                "INSERT INTO analytics_closure_projection (id, work_order_id, asset_id, fault_key, is_first_time_fix, maturity, closed_at, matured_at, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), ?, ?, 'IDEM-FC', TRUE, 'PROVISIONAL', ?, ?, now(), now())",
                earlierWoId, assetId, earlierClosed, earlierClosed.plus(Duration.ofDays(30)));

        UUID laterWoId = UUID.randomUUID();
        Instant laterClosed = earlierClosed.plus(Duration.ofDays(3));
        jdbc.update(
                "INSERT INTO work_order (id, tenant_id, title, state, priority, asset_id, fault_code, created_at, updated_at, version) " +
                "VALUES (?, '00000000-0000-0000-0000-000000000001', 'Idem later', 'CLOSED', 'LOW', ?, 'IDEM-FC', ?, ?, 0)",
                laterWoId, assetId, laterClosed, laterClosed);

        // First call
        repeatVisitLinker.processClosureEvent(laterWoId, laterClosed);
        // Replay — should not create a second link
        repeatVisitLinker.processClosureEvent(laterWoId, laterClosed);

        Integer linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repeat_visit_link WHERE later_work_order_id = ?",
                Integer.class, laterWoId);
        assertThat(linkCount).isEqualTo(1);
    }
}
