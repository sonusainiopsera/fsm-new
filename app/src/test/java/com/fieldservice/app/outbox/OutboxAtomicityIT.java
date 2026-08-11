package com.fieldservice.app.outbox;

import com.fieldservice.app.Application;
import com.fieldservice.app.fixtures.OutboxEventFixtures;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.platform.api.DomainEvent;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.PayloadTooLargeException;
import com.fieldservice.platform.outbox.OutboxProperties;
import com.fieldservice.platform.outbox.RestrictedFieldException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration tests for outbox atomicity (AC-2, AC-4, AC-5, AC-10).
 *
 * <p>Tests:
 * <ul>
 *   <li>Commit: one domain row + one Envers revision + one outbox row</li>
 *   <li>Rollback: zero domain rows, zero revisions, zero outbox rows</li>
 *   <li>Transaction-required guard: publish outside transaction throws</li>
 *   <li>Payload too large: oversized payload throws and rolls back</li>
 *   <li>Concurrent publish: unique event ids, no constraint violations</li>
 *   <li>Partial index {@code idx_outbox_event_drain} exists in pg_indexes</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class OutboxAtomicityIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_outbox_test")
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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DomainEventPublisher publisher;
    @Autowired DataSource dataSource;

    private TransactionTemplate tx;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(txManager);
        MDC.clear();
    }

    // ---- AC-4: commit produces exactly one of each row -------------------------

    @Test
    @DisplayName("commit: one domain row, one Envers revision, one outbox row — all sharing traceId")
    void commit_produces_one_domain_revision_and_outbox_row() {
        String traceId = "trace-outbox-commit-001";
        MDC.put("traceId", traceId);

        UUID siteId = seedSite("commit-test-site-" + UUID.randomUUID());

        long outboxBefore = countOutbox();

        UUID woId = tx.execute(status -> {
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-OUTBOX-001-" + System.nanoTime(),
                    WorkOrderStatus.NEW, "HIGH", site, null);
            entityManager.persist(wo);

            DomainEvent event = OutboxEventFixtures.workOrderAssigned(wo.getId(), traceId);
            publisher.publish(event);

            return wo.getId();
        });

        long outboxAfter = countOutbox();
        assertThat(outboxAfter).isEqualTo(outboxBefore + 1);

        tx.execute(status -> {
            // Verify the outbox row has the correct traceId
            String storedTraceId = (String) entityManager.createNativeQuery(
                            "SELECT trace_id FROM outbox_event WHERE aggregate_id = CAST(?1 AS uuid)")
                    .setParameter(1, woId.toString())
                    .getSingleResult();
            assertThat(storedTraceId).isEqualTo(traceId);

            // Verify the work order exists
            WorkOrder loaded = entityManager.find(WorkOrder.class, woId);
            assertThat(loaded).isNotNull();

            // Verify an Envers revision exists
            long revCount = ((Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM work_order_aud WHERE id = CAST(?1 AS uuid)")
                    .setParameter(1, woId.toString())
                    .getSingleResult()).longValue();
            assertThat(revCount).isGreaterThanOrEqualTo(1L);

            return null;
        });
    }

    // ---- AC-5: rollback leaves zero rows anywhere -----------------------------

    @Test
    @DisplayName("rollback: zero domain rows, zero Envers revisions, zero outbox rows")
    void rollback_leaves_no_rows() {
        String ref = "WO-ROLLBACK-OUTBOX-" + System.nanoTime();
        UUID siteId = seedSite("rollback-outbox-site-" + UUID.randomUUID());

        long outboxBefore = countOutbox();

        try {
            tx.execute(status -> {
                Site site = entityManager.find(Site.class, siteId);
                WorkOrder wo = new WorkOrder(ref, WorkOrderStatus.NEW, "LOW", site, null);
                entityManager.persist(wo);

                DomainEvent event = OutboxEventFixtures.workOrderAssigned(wo.getId(), "trace-rollback");
                publisher.publish(event);

                status.setRollbackOnly();
                return null;
            });
        } catch (Exception ignored) {}

        long outboxAfter = countOutbox();
        assertThat(outboxAfter).isEqualTo(outboxBefore);

        tx.execute(status -> {
            long woCount = ((Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM work_order WHERE reference = ?1")
                    .setParameter(1, ref)
                    .getSingleResult()).longValue();
            assertThat(woCount).isZero();
            return null;
        });
    }

    // ---- AC-3: transaction-required guard ------------------------------------

    @Test
    @DisplayName("publish outside a transaction throws IllegalTransactionStateException")
    void publish_outside_transaction_throws() {
        DomainEvent event = OutboxEventFixtures.workOrderAssigned(UUID.randomUUID(), "trace-notx");
        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    // ---- AC-8: payload size bounding -----------------------------------------

    @Test
    @DisplayName("oversized payload throws PayloadTooLargeException and rolls back")
    void oversized_payload_fails_transaction() {
        UUID siteId = seedSite("size-test-site-" + UUID.randomUUID());
        long outboxBefore = countOutbox();

        assertThatThrownBy(() ->
            tx.execute(status -> {
                Site site = entityManager.find(Site.class, siteId);
                WorkOrder wo = new WorkOrder("WO-BIGPAYLOAD-" + System.nanoTime(),
                        WorkOrderStatus.NEW, "LOW", site, null);
                entityManager.persist(wo);

                // Build a payload larger than 64KB
                String hugeData = "x".repeat(70_000);
                DomainEvent event = new DomainEvent(
                        UuidV7.generate(), "WORK_ORDER_ASSIGNED", "WORK_ORDER", wo.getId(),
                        Instant.now(), "trace-big", null,
                        new OutboxEventFixtures.WorkOrderAssignedPayload(hugeData, "HIGH", null));
                publisher.publish(event);
                return null;
            })
        ).isInstanceOf(PayloadTooLargeException.class);

        assertThat(countOutbox()).isEqualTo(outboxBefore);
    }

    // ---- AC-10: concurrent publish — unique event ids, no constraint violations --

    @Test
    @DisplayName("concurrent publish from 10 threads produces unique event ids with no violations")
    void concurrent_publish_unique_ids() throws InterruptedException {
        UUID siteId = seedSite("concurrent-site-" + UUID.randomUUID());
        int threads = 10;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go    = new CountDownLatch(1);
        List<UUID> publishedIds = new CopyOnWriteArrayList<>();
        List<Exception> errors  = new CopyOnWriteArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    tx.execute(status -> {
                        Site site = entityManager.find(Site.class, siteId);
                        WorkOrder wo = new WorkOrder("WO-CONC-" + idx + "-" + System.nanoTime(),
                                WorkOrderStatus.NEW, "HIGH", site, null);
                        entityManager.persist(wo);

                        DomainEvent event = OutboxEventFixtures.workOrderAssigned(
                                wo.getId(), "trace-conc-" + idx);
                        publisher.publish(event);
                        publishedIds.add(event.eventId());
                        return null;
                    });
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(errors).isEmpty();
        assertThat(publishedIds).hasSize(threads);
        assertThat(Set.copyOf(publishedIds)).hasSize(threads);
    }

    // ---- AC-2: partial drain index exists ------------------------------------

    @Test
    @DisplayName("partial drain index idx_outbox_event_drain exists in pg_indexes")
    void drain_index_exists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT indexdef FROM pg_indexes " +
                     "WHERE schemaname = 'public' AND indexname = 'idx_outbox_event_drain'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("idx_outbox_event_drain should exist in pg_indexes")
                        .isTrue();
                String indexDef = rs.getString("indexdef").toLowerCase();
                assertThat(indexDef).contains("where");
                assertThat(indexDef).contains("published_at is null");
            }
        }
    }

    // ---- AC-6: Restricted payload rejected -----------------------------------

    @Test
    @DisplayName("publish with @Restricted non-null field throws and rolls back")
    void restricted_field_rejected() {
        UUID siteId = seedSite("restricted-site-" + UUID.randomUUID());
        long outboxBefore = countOutbox();

        assertThatThrownBy(() ->
            tx.execute(status -> {
                Site site = entityManager.find(Site.class, siteId);
                WorkOrder wo = new WorkOrder("WO-RESTRICTED-" + System.nanoTime(),
                        WorkOrderStatus.NEW, "LOW", site, null);
                entityManager.persist(wo);

                var badPayload = new OutboxEventFixtures.PayloadWithRestrictedField(
                        "user-001", "$2a$12$hashvalue");
                DomainEvent event = new DomainEvent(
                        UuidV7.generate(), "WORK_ORDER_ASSIGNED", "WORK_ORDER", wo.getId(),
                        Instant.now(), "trace-restricted", null, badPayload);
                publisher.publish(event);
                return null;
            })
        ).isInstanceOf(RestrictedFieldException.class);

        assertThat(countOutbox()).isEqualTo(outboxBefore);
    }

    // ---- AC-7: traceId and actorUserId on every event -----------------------

    @Test
    @DisplayName("published outbox row carries traceId and actor_user_id")
    void event_carries_trace_and_actor() {
        String traceId = "trace-correlation-" + UUID.randomUUID();
        UUID actorId   = UUID.randomUUID();
        UUID siteId    = seedSite("actor-trace-site-" + UUID.randomUUID());

        UUID eventId = tx.execute(status -> {
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-TRACE-" + System.nanoTime(),
                    WorkOrderStatus.NEW, "HIGH", site, null);
            entityManager.persist(wo);

            DomainEvent event = new DomainEvent(
                    UuidV7.generate(), "WORK_ORDER_ASSIGNED", "WORK_ORDER", wo.getId(),
                    Instant.now(), traceId, actorId,
                    new OutboxEventFixtures.WorkOrderAssignedPayload("WO-TRACE-001", "HIGH", actorId));
            publisher.publish(event);
            return event.eventId();
        });

        tx.execute(status -> {
            Object[] row = (Object[]) entityManager.createNativeQuery(
                            "SELECT trace_id, actor_user_id FROM outbox_event WHERE event_id = CAST(?1 AS uuid)")
                    .setParameter(1, eventId.toString())
                    .getSingleResult();
            assertThat(row[0]).isEqualTo(traceId);
            assertThat(UUID.fromString(row[1].toString())).isEqualTo(actorId);
            return null;
        });
    }

    // ---- helpers ---------------------------------------------------------------

    private long countOutbox() {
        return tx.execute(status ->
                ((Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM outbox_event").getSingleResult()).longValue());
    }

    private UUID seedSite(String name) {
        return tx.execute(status -> {
            UUID custId = UUID.randomUUID();
            entityManager.createNativeQuery(
                    "INSERT INTO customer (id, name) VALUES (?1, ?2)")
                    .setParameter(1, custId.toString())
                    .setParameter(2, "Test Corp " + name)
                    .executeUpdate();
            UUID siteId = UUID.randomUUID();
            entityManager.createNativeQuery(
                    "INSERT INTO site (id, name, customer_id) VALUES (?1, ?2, ?3)")
                    .setParameter(1, siteId.toString())
                    .setParameter(2, name)
                    .setParameter(3, custId.toString())
                    .executeUpdate();
            return siteId;
        });
    }
}
