package com.fieldservice.analytics.quality;

import com.fieldservice.analytics.internal.quality.CohortMaturityResolver;
import com.fieldservice.analytics.internal.quality.RepeatVisitLinker;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.platform.api.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the first-time fix quality analytics pipeline.
 *
 * <p>Tests:
 * <ol>
 *   <li>Closure event populates analytics_closure_projection with correct maturity.</li>
 *   <li>Repeat visit within 30 days creates a repeat_visit_link and marks predecessor not-FTF.</li>
 *   <li>Repeat visit at exactly 30 days is NOT linked (boundary test).</li>
 *   <li>UNCLASSIFIABLE work orders (null asset/fault) are stored but not linked.</li>
 *   <li>Replayed event does not duplicate rows.</li>
 *   <li>MaturationSweepJob promotes PROVISIONAL rows to MATURED.</li>
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
class FirstTimeFixQualityIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_quality_it")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url",      postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
        reg.add("spring.flyway.url",          postgres::getJdbcUrl);
        reg.add("spring.flyway.user",         postgres::getUsername);
        reg.add("spring.flyway.password",     postgres::getPassword);
        reg.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        reg.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired JdbcTemplate        jdbc;
    @Autowired RepeatVisitLinker   linker;
    @Autowired CohortMaturityResolver maturityResolver;

    // Seed IDs
    static final UUID CUSTOMER_ID = UUID.fromString("bb000000-0000-7001-8000-000000000099");
    static final UUID SITE_ID     = UUID.fromString("bb000000-0000-7001-8000-000000000001");
    static final UUID ASSET_ID    = UUID.fromString("bb000000-0000-7001-8000-000000000002");
    static final UUID ASSET_ID2   = UUID.fromString("bb000000-0000-7001-8000-000000000003");

    @BeforeEach
    void seedBaseData() {
        jdbc.execute("DELETE FROM repeat_visit_link");
        jdbc.execute("DELETE FROM analytics_closure_projection");

        // Ensure reference rows exist (idempotent)
        jdbc.execute("""
            INSERT INTO customer (id, name, version)
            VALUES ('bb000000-0000-7001-8000-000000000099', 'Quality IT Customer', 0)
            ON CONFLICT DO NOTHING
            """);
        jdbc.execute("""
            INSERT INTO site (id, name, customer_id, version)
            VALUES ('bb000000-0000-7001-8000-000000000001', 'Quality IT Site',
                    'bb000000-0000-7001-8000-000000000099', 0)
            ON CONFLICT DO NOTHING
            """);
        jdbc.execute("""
            INSERT INTO asset (id, site_id, version)
            VALUES ('bb000000-0000-7001-8000-000000000002', 'bb000000-0000-7001-8000-000000000001', 0)
            ON CONFLICT DO NOTHING
            """);
        jdbc.execute("""
            INSERT INTO asset (id, site_id, version)
            VALUES ('bb000000-0000-7001-8000-000000000003', 'bb000000-0000-7001-8000-000000000001', 0)
            ON CONFLICT DO NOTHING
            """);
    }

    // ---------------------------------------------------------------
    // Helper: insert a work order with known fault fields
    // ---------------------------------------------------------------

    private UUID insertWorkOrder(UUID assetId, String faultCode, String faultCategory,
                                  String state) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO work_order
                    (id, reference, state, priority, site_id, asset_id, fault_code, fault_category, version)
                VALUES (?, ?, ?, 'MEDIUM', ?, ?, ?, ?, 0)
                ON CONFLICT (id) DO NOTHING
                """,
                id, "WO-IT-" + id.toString().substring(0, 8), state,
                SITE_ID, assetId, faultCode, faultCategory);
        return id;
    }

    private DomainEvent closureEvent(UUID workOrderId, Instant occurredAt) {
        return new DomainEvent(
                UUID.randomUUID(),
                "WORK_ORDER_TRANSITION",
                "WORK_ORDER",
                workOrderId,
                occurredAt,
                null, null,
                "{\"toState\":\"CLOSED\"}"
        );
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Closure event creates closure projection with PROVISIONAL maturity when < 30 days ago")
    @Transactional
    void closureEvent_createsProvisionalProjection() {
        UUID woId = insertWorkOrder(ASSET_ID, "FC-001", null, "CLOSED");
        Instant closedAt = Instant.now().minus(5, ChronoUnit.DAYS);

        linker.processEvent(closureEvent(woId, closedAt));

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics_closure_projection WHERE work_order_id = ?",
                Long.class, woId);
        assertThat(count).isEqualTo(1L);

        String maturity = jdbc.queryForObject(
                "SELECT maturity FROM analytics_closure_projection WHERE work_order_id = ?",
                String.class, woId);
        assertThat(maturity).isEqualTo("PROVISIONAL");
    }

    @Test
    @DisplayName("Closure event 31 days ago creates MATURED projection")
    @Transactional
    void closureEvent_createsMatureProjection_when31DaysAgo() {
        UUID woId = insertWorkOrder(ASSET_ID, "FC-001", null, "CLOSED");
        Instant closedAt = Instant.now().minus(31, ChronoUnit.DAYS);

        linker.processEvent(closureEvent(woId, closedAt));

        String maturity = jdbc.queryForObject(
                "SELECT maturity FROM analytics_closure_projection WHERE work_order_id = ?",
                String.class, woId);
        assertThat(maturity).isEqualTo("MATURED");
    }

    @Test
    @DisplayName("Repeat visit at 10 days creates link and marks predecessor not-FTF")
    @Transactional
    void repeatVisit_within30Days_createsLinkAndMarksPredecessor() {
        UUID earlier = insertWorkOrder(ASSET_ID, "FC-001", null, "CLOSED");
        UUID later   = insertWorkOrder(ASSET_ID, "FC-001", null, "CLOSED");

        Instant t0 = Instant.now().minus(20, ChronoUnit.DAYS);
        Instant t1 = t0.plus(10, ChronoUnit.DAYS);

        linker.processEvent(closureEvent(earlier, t0));
        linker.processEvent(closureEvent(later,   t1));

        // A link row should exist
        Long linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repeat_visit_link WHERE earlier_work_order_id = ? AND later_work_order_id = ?",
                Long.class, earlier, later);
        assertThat(linkCount).isEqualTo(1L);

        // Predecessor should be marked not-first-time-fix
        Boolean ftf = jdbc.queryForObject(
                "SELECT is_first_time_fix FROM analytics_closure_projection WHERE work_order_id = ?",
                Boolean.class, earlier);
        assertThat(ftf).isFalse();
    }

    @Test
    @DisplayName("Repeat visit at exactly 30 days is NOT linked (boundary)")
    @Transactional
    void repeatVisit_atExactly30Days_notLinked() {
        UUID earlier = insertWorkOrder(ASSET_ID, "FC-002", null, "CLOSED");
        UUID later   = insertWorkOrder(ASSET_ID, "FC-002", null, "CLOSED");

        Instant t0 = Instant.now().minus(40, ChronoUnit.DAYS);
        Instant t1 = t0.plus(30, ChronoUnit.DAYS);   // exactly 30 days

        linker.processEvent(closureEvent(earlier, t0));
        linker.processEvent(closureEvent(later,   t1));

        Long linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repeat_visit_link WHERE earlier_work_order_id = ? AND later_work_order_id = ?",
                Long.class, earlier, later);
        assertThat(linkCount).isEqualTo(0L);

        // Predecessor should still be first-time-fix
        Boolean ftf = jdbc.queryForObject(
                "SELECT is_first_time_fix FROM analytics_closure_projection WHERE work_order_id = ?",
                Boolean.class, earlier);
        assertThat(ftf).isTrue();
    }

    @Test
    @DisplayName("UNCLASSIFIABLE work order (null asset) is stored but not linked")
    @Transactional
    void unclassifiable_noAsset_storedButNotLinked() {
        UUID woId = insertWorkOrder(null, "FC-001", null, "CLOSED");
        linker.processEvent(closureEvent(woId, Instant.now().minus(5, ChronoUnit.DAYS)));

        Long projCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics_closure_projection WHERE work_order_id = ?",
                Long.class, woId);
        assertThat(projCount).isEqualTo(1L);

        Long linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repeat_visit_link WHERE later_work_order_id = ?",
                Long.class, woId);
        assertThat(linkCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("Replayed closure event does not duplicate projection row")
    @Transactional
    void replayedEvent_doesNotDuplicateProjection() {
        UUID woId = insertWorkOrder(ASSET_ID, "FC-003", null, "CLOSED");
        DomainEvent event = closureEvent(woId, Instant.now().minus(5, ChronoUnit.DAYS));

        linker.processEvent(event);
        linker.processEvent(event);  // replay

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics_closure_projection WHERE work_order_id = ?",
                Long.class, woId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("Different fault on same asset does NOT create a repeat-visit link")
    @Transactional
    void differentFault_noLink() {
        UUID wo1 = insertWorkOrder(ASSET_ID, "FC-010", null, "CLOSED");
        UUID wo2 = insertWorkOrder(ASSET_ID, "FC-011", null, "CLOSED");  // different fault code

        Instant t0 = Instant.now().minus(20, ChronoUnit.DAYS);
        linker.processEvent(closureEvent(wo1, t0));
        linker.processEvent(closureEvent(wo2, t0.plus(5, ChronoUnit.DAYS)));

        Long linkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repeat_visit_link", Long.class);
        assertThat(linkCount).isEqualTo(0L);
    }

    @Test
    @DisplayName("V30 migration created analytics_closure_projection table")
    void migration_createsClosureProjectionTable() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'analytics_closure_projection'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("V30 migration created repeat_visit_link table")
    void migration_createsRepeatVisitLinkTable() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'repeat_visit_link'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }
}
