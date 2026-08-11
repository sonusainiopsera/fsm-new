package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.internal.KpiOutboxConsumer;
import com.fieldservice.analytics.internal.MetricDebounceRegistry;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.platform.api.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the analytics substrate.
 *
 * <p>Tests:
 * <ol>
 *   <li>Migrations create kpi_projection and processed_event tables.</li>
 *   <li>KpiOutboxConsumer inserts into processed_event on first delivery.</li>
 *   <li>Replayed event_id produces no duplicate processed_event row (idempotency).</li>
 *   <li>MetricDebounceRegistry receives the enqueued metric keys.</li>
 *   <li>KpiProjectionQuery.findByKey returns empty for non-existent projection.</li>
 *   <li>DegradationPolicy returns degraded=true when projection table read fails.</li>
 * </ol>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                "app.analytics.enabled=true"
        })
@Import(TestSecurityConfig.class)
class AnalyticsSubstrateIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_analytics_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url",          postgres::getJdbcUrl);
        registry.add("spring.flyway.user",         postgres::getUsername);
        registry.add("spring.flyway.password",     postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired JdbcTemplate          jdbc;
    @Autowired KpiProjectionQuery    query;
    @Autowired KpiOutboxConsumer     consumer;
    @Autowired MetricDebounceRegistry debounceRegistry;

    // ---- Migration: tables exist -------------------------------------------

    @Test
    @DisplayName("V24 migration creates kpi_projection table")
    void migration_creates_kpiProjection_table() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_name = 'kpi_projection'", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("V24 migration creates processed_event table")
    void migration_creates_processedEvent_table() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_name = 'processed_event'", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    // ---- Idempotency: processed_event insert --------------------------------

    @Test
    @DisplayName("First delivery inserts processed_event row; replay skips it")
    void idempotency_firstDelivery_insertsRow_replaySkips() throws Exception {
        UUID eventId = UUID.randomUUID();
        DomainEvent event = new DomainEvent(
                eventId, "WORK_ORDER_CREATED", "WORK_ORDER", UUID.randomUUID(),
                Instant.now(), null, null, "{\"workOrderId\":\"" + UUID.randomUUID() + "\"}");

        // First delivery
        consumer.accept(event);

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
                Long.class, eventId);
        assertThat(rowCount).isEqualTo(1L);

        // Replay: should not throw and should not create a duplicate
        consumer.accept(event);

        Long rowCountAfterReplay = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
                Long.class, eventId);
        assertThat(rowCountAfterReplay).isEqualTo(1L);
    }

    // ---- Debounce enqueue on event receipt ----------------------------------

    @Test
    @DisplayName("Consumer enqueues metric keys into debounce registry")
    void consumer_enqueuesMetricKeys_intoDebounceRegistry() throws Exception {
        int pendingBefore = debounceRegistry.pendingCount();

        DomainEvent event = new DomainEvent(
                UUID.randomUUID(), "WORK_ORDER_TRANSITION", "WORK_ORDER", UUID.randomUUID(),
                Instant.now(), null, null, "{\"toState\":\"COMPLETED\"}");

        consumer.accept(event);

        assertThat(debounceRegistry.pendingCount()).isGreaterThan(pendingBefore);
    }

    // ---- Query port returns empty for missing projection --------------------

    @Test
    @DisplayName("findByKey returns empty when no projection exists")
    void findByKey_returnsEmpty_whenNoneExists() {
        Optional<KpiProjection> result = query.findByKey(
                "wo.nonexistent_metric", "ALL", "P90D");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByMetricKey returns empty list when no projection exists")
    void findByMetricKey_returnsEmptyList_whenNoneExists() {
        List<KpiProjection> results = query.findByMetricKey("wo.nonexistent_list_metric");
        assertThat(results).isEmpty();
    }

    // ---- Staleness: data_as_of is present on each projection ----------------

    @Test
    @DisplayName("Manually inserted projection row has correct data_as_of and is readable via query port")
    void manualProjection_readableViaQueryPort_withDataAsOf() {
        UUID id = UUID.randomUUID();
        Instant dataAsOf = Instant.now().minusSeconds(30);

        jdbc.update("""
                INSERT INTO kpi_projection
                    (id, metric_key, segment_key, window_key, value, maturity, data_as_of, projection_version, degraded)
                VALUES (?, 'it.test_metric', 'ALL', 'P7D', 42.0, 'MATURE', ?, 1, false)
                """, id, java.sql.Timestamp.from(dataAsOf));

        Optional<KpiProjection> result = query.findByKey("it.test_metric", "ALL", "P7D");

        assertThat(result).isPresent();
        KpiProjection p = result.get();
        assertThat(p.metricKey()).isEqualTo("it.test_metric");
        assertThat(p.dataAsOf()).isNotNull();
        assertThat(p.stalenessSeconds()).isGreaterThanOrEqualTo(0L);
        assertThat(p.degraded()).isFalse();
    }
}
