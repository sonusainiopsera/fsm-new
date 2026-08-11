package com.fieldservice.app.audit;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.platform.audit.AppRevision;
import com.fieldservice.platform.audit.AppRevisionListener;
import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test verifying the full Envers audit chain.
 *
 * <p>Creates, updates, and deletes entities via JPA and asserts:
 * <ul>
 *   <li>One revision row per committing transaction (AC-5)</li>
 *   <li>Actor fields populated from JWT SecurityContext (AC-3)</li>
 *   <li>Delete revision retains last-known state (AC-7)</li>
 *   <li>Rolled-back transaction leaves no audit row (AC-7)</li>
 *   <li>Two entities mutated in one transaction share a single REVINFO row (AC-5)</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class AuditRevisionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_audit_test")
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

    private TransactionTemplate tx;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(txManager);
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    // ---- AC-3: actor fields populated ------------------------------------------------

    @Test
    @DisplayName("creating a work order populates actor fields from JWT SecurityContext")
    void create_revision_carries_actor_from_jwt() {
        setJwtAuth("actor-001", "ROLE_DISPATCHER");
        MDC.put("traceId", "trace-001");
        MDC.put("clientIp", "10.0.0.1");

        UUID siteId = seedSite("actor-test-site");
        UUID woId = tx.execute(status -> {
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-ACTOR-001", WorkOrderStatus.NEW, "HIGH",
                    site, null);
            entityManager.persist(wo);
            return wo.getId();
        });

        tx.execute(status -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Object[]> rows = reader.createQuery()
                    .forRevisionsOfEntity(WorkOrder.class, false, true)
                    .add(AuditEntity.id().eq(woId))
                    .getResultList();
            assertThat(rows).hasSize(1);
            AppRevision rev = (AppRevision) rows.get(0)[1];
            assertThat(rev.getActorUserId()).isEqualTo("actor-001");
            assertThat(rev.getActorRole()).isEqualTo("DISPATCHER");
            assertThat(rev.getTraceId()).isEqualTo("trace-001");
            assertThat(rev.getClientIp()).isEqualTo("10.0.0.1");
            return null;
        });
    }

    // ---- AC-5: one revision per transaction ------------------------------------------

    @Test
    @DisplayName("create + update in separate transactions produce two distinct revisions")
    void two_transactions_produce_two_revisions() {
        setJwtAuth("actor-002", "ROLE_DISPATCHER");
        UUID siteId = seedSite("two-rev-site");

        UUID woId = tx.execute(status -> {
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-TWO-001", WorkOrderStatus.NEW, "LOW", site, null);
            entityManager.persist(wo);
            return wo.getId();
        });

        tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, woId);
            wo.assignTechnician(UUID.randomUUID());
            return null;
        });

        tx.execute(status -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Object[]> rows = reader.createQuery()
                    .forRevisionsOfEntity(WorkOrder.class, false, true)
                    .add(AuditEntity.id().eq(woId))
                    .getResultList();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)[2]).isEqualTo(RevisionType.ADD);
            assertThat(rows.get(1)[2]).isEqualTo(RevisionType.MOD);
            return null;
        });
    }

    @Test
    @DisplayName("two entities mutated in one transaction share exactly one REVINFO row")
    void single_transaction_produces_one_shared_revinfo_row() {
        setJwtAuth("actor-003", "ROLE_MANAGER");
        UUID siteId = seedSite("shared-rev-site");

        tx.execute(status -> {
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo1 = new WorkOrder("WO-SHARED-001", WorkOrderStatus.NEW, "HIGH", site, null);
            WorkOrder wo2 = new WorkOrder("WO-SHARED-002", WorkOrderStatus.NEW, "LOW",  site, null);
            entityManager.persist(wo1);
            entityManager.persist(wo2);
            return null;
        });

        tx.execute(status -> {
            var reader = AuditReaderFactory.get(entityManager);
            // Both work orders should reference the same REV number
            List<Number> revs1 = reader.getRevisions(WorkOrder.class,
                    entityManager.createQuery(
                            "SELECT wo.id FROM WorkOrder wo WHERE wo.reference = 'WO-SHARED-001'",
                            UUID.class).getSingleResult());
            List<Number> revs2 = reader.getRevisions(WorkOrder.class,
                    entityManager.createQuery(
                            "SELECT wo.id FROM WorkOrder wo WHERE wo.reference = 'WO-SHARED-002'",
                            UUID.class).getSingleResult());
            assertThat(revs1).hasSize(1);
            assertThat(revs2).hasSize(1);
            assertThat(revs1.get(0)).isEqualTo(revs2.get(0));
            return null;
        });
    }

    // ---- AC-7: delete revision retains state ----------------------------------------

    @Test
    @DisplayName("deleting a work order produces a DEL revision retaining last-known state")
    void delete_produces_del_revision_with_last_state() {
        setJwtAuth("actor-004", "ROLE_DISPATCHER");
        UUID siteId = seedSite("del-test-site");

        UUID woId = tx.execute(status -> {
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-DEL-001", WorkOrderStatus.NEW, "CRITICAL", site, null);
            entityManager.persist(wo);
            return wo.getId();
        });

        tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, woId);
            entityManager.remove(wo);
            return null;
        });

        tx.execute(status -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Object[]> rows = reader.createQuery()
                    .forRevisionsOfEntity(WorkOrder.class, false, true)
                    .add(AuditEntity.id().eq(woId))
                    .getResultList();
            assertThat(rows).hasSize(2);
            Object[] delRow = rows.get(1);
            assertThat(delRow[2]).isEqualTo(RevisionType.DEL);
            WorkOrder snapshot = (WorkOrder) delRow[0];
            assertThat(snapshot.getReference()).isEqualTo("WO-DEL-001");
            assertThat(snapshot.getPriority()).isEqualTo("CRITICAL");
            return null;
        });
    }

    // ---- AC-7: rollback leaves no audit rows ----------------------------------------

    @Test
    @DisplayName("a rolled-back transaction leaves no audit or REVINFO rows")
    void rollback_produces_no_audit_rows() {
        setJwtAuth("actor-005", "ROLE_DISPATCHER");
        UUID siteId = seedSite("rollback-site");

        long revsBefore = tx.execute(status ->
                ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM REVINFO")
                        .getSingleResult()).longValue());

        try {
            tx.execute(status -> {
                Site site = entityManager.find(Site.class, siteId);
                WorkOrder wo = new WorkOrder("WO-ROLLBACK-001", WorkOrderStatus.NEW, "LOW", site, null);
                entityManager.persist(wo);
                // Force rollback
                status.setRollbackOnly();
                return null;
            });
        } catch (Exception ignored) {}

        long revsAfter = tx.execute(status ->
                ((Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM REVINFO")
                        .getSingleResult()).longValue());

        assertThat(revsAfter).isEqualTo(revsBefore);
    }

    // ---- AC-3: no-auth system actor -------------------------------------------------

    @Test
    @DisplayName("revision created with no authentication records SYSTEM actor")
    void no_auth_records_system_actor() {
        SecurityContextHolder.clearContext(); // ensure no auth
        UUID siteId = seedSite("system-actor-site");

        UUID woId = tx.execute(status -> {
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-SYSTEM-001", WorkOrderStatus.NEW, "LOW", site, null);
            entityManager.persist(wo);
            return wo.getId();
        });

        tx.execute(status -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Object[]> rows = reader.createQuery()
                    .forRevisionsOfEntity(WorkOrder.class, false, true)
                    .add(AuditEntity.id().eq(woId))
                    .getResultList();
            AppRevision rev = (AppRevision) rows.get(0)[1];
            assertThat(rev.getActorUserId()).isEqualTo(AppRevisionListener.SYSTEM_ACTOR);
            return null;
        });
    }

    // ---- helpers ----------------------------------------------------------------

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

    private static void setJwtAuth(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }
}
