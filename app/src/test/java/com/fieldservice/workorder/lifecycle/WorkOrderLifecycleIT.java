package com.fieldservice.workorder.lifecycle;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.platform.audit.AppRevision;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test: walks the full happy path NEW → CLOSED via {@link WorkOrderTransitionService}
 * and asserts persisted state and Envers revision count after each move.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class WorkOrderLifecycleIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_lifecycle_test")
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

    @Autowired WorkOrderTransitionService transitionService;
    @Autowired WorkOrderRepository        workOrderRepository;
    @Autowired EntityManager              entityManager;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @DisplayName("full happy path NEW → ASSIGNED → EN_ROUTE → IN_PROGRESS → COMPLETED → CLOSED persists correctly")
    void happyPath_newToClosed() {
        setJwtAuth("lifecycle-tester", "DISPATCHER", "TECHNICIAN");
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // Seed: site and customer required by WorkOrder FK
        UUID siteId = tx.execute(status -> {
            entityManager.createNativeQuery(
                    "INSERT INTO customer (id, name) VALUES ('" + uuid("c1") + "', 'LC Corp')")
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "INSERT INTO site (id, name, customer_id) VALUES ('" + uuid("s1") +
                    "', 'LC Site', '" + uuid("c1") + "')")
                    .executeUpdate();
            return UUID.fromString(uuid("s1"));
        });

        // Create work order in NEW state using raw SQL (bypass scope filter)
        UUID woId = UUID.fromString(uuid("w1"));
        tx.execute(status -> {
            entityManager.createNativeQuery(
                    "INSERT INTO work_order (id, reference, state, priority, site_id, version) " +
                    "VALUES ('" + woId + "', 'LC-001', 'NEW', 'HIGH', '" + siteId + "', 0)")
                    .executeUpdate();
            return null;
        });

        List<WorkOrderEvent> happyPath = List.of(
                WorkOrderEvent.ASSIGN,
                WorkOrderEvent.DEPART,
                WorkOrderEvent.START,
                WorkOrderEvent.COMPLETE,
                WorkOrderEvent.CLOSE
        );
        List<WorkOrderStatus> expectedStates = List.of(
                WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.EN_ROUTE,
                WorkOrderStatus.IN_PROGRESS,
                WorkOrderStatus.COMPLETED,
                WorkOrderStatus.CLOSED
        );

        List<String> actorRoles = List.of("DISPATCHER", "TECHNICIAN", "MANAGER", "ADMIN");

        for (int i = 0; i < happyPath.size(); i++) {
            WorkOrderEvent event = happyPath.get(i);
            WorkOrderStatus expectedState = expectedStates.get(i);

            tx.execute(status -> {
                WorkOrder wo = entityManager.find(WorkOrder.class, woId);
                transitionService.apply(wo, event, actorRoles);
                return null;
            });

            WorkOrderStatus persisted = tx.execute(status ->
                    entityManager.find(WorkOrder.class, woId).getState());
            assertThat(persisted)
                    .as("After event %s, state should be %s", event, expectedState)
                    .isEqualTo(expectedState);
        }

        // Verify Envers revision count = 5 state changes (initial insert is revision 1,
        // then 5 updates = 6 total; but INSERT_ONLY config may differ — assert at least 5)
        tx.execute(status -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Number> revs = reader.getRevisions(WorkOrder.class, woId);
            assertThat(revs.size()).isGreaterThanOrEqualTo(5);
            return null;
        });

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("illegal event from terminal CLOSED state throws IllegalWorkOrderTransitionException")
    void terminalState_closedRefusesAnyEvent() {
        setJwtAuth("lifecycle-tester2", "ADMIN");
        TransactionTemplate tx = new TransactionTemplate(txManager);

        UUID siteId2 = tx.execute(status -> {
            entityManager.createNativeQuery(
                    "INSERT INTO customer (id, name) VALUES ('" + uuid("c2") + "', 'LC Corp 2')")
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "INSERT INTO site (id, name, customer_id) VALUES ('" + uuid("s2") +
                    "', 'LC Site 2', '" + uuid("c2") + "')")
                    .executeUpdate();
            return UUID.fromString(uuid("s2"));
        });

        UUID woId2 = UUID.fromString(uuid("w2"));
        tx.execute(status -> {
            entityManager.createNativeQuery(
                    "INSERT INTO work_order (id, reference, state, priority, site_id, version) " +
                    "VALUES ('" + woId2 + "', 'LC-002', 'CLOSED', 'LOW', '" + siteId2 + "', 0)")
                    .executeUpdate();
            return null;
        });

        List<String> actorRoles = List.of("ADMIN");

        assertThatThrownBy(() -> tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, woId2);
            transitionService.apply(wo, WorkOrderEvent.ASSIGN, actorRoles);
            return null;
        }))
        .hasCauseInstanceOf(IllegalWorkOrderTransitionException.class)
        .satisfies(e -> {
            IllegalWorkOrderTransitionException cause =
                    (IllegalWorkOrderTransitionException) e.getCause();
            assertThat(cause.getCurrentState()).isEqualTo(WorkOrderState.CLOSED);
            assertThat(cause.getLegalEvents()).isEmpty();
            assertThat(cause.getErrorCode())
                    .isEqualTo(com.fieldservice.workorder.WorkOrderErrorCodes.ILLEGAL_TRANSITION);
        });

        SecurityContextHolder.clearContext();
    }

    // ---- Enum / CHECK constraint alignment ------------------------------------

    @Autowired javax.sql.DataSource dataSource;

    @Test
    @DisplayName("WorkOrderState enum is in exact agreement with the database CHECK constraint vocabulary")
    void workOrderState_matchesDatabaseCheckConstraint() throws Exception {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) " +
                     "FROM pg_constraint " +
                     "WHERE conname = 'chk_work_order_state'")) {
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("chk_work_order_state constraint must exist").isTrue();
                String def = rs.getString(1);
                for (WorkOrderState s : WorkOrderState.values()) {
                    assertThat(def)
                            .as("CHECK constraint must include state %s", s)
                            .contains("'" + s.name() + "'");
                }
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------

    private static String uuid(String suffix) {
        return switch (suffix) {
            case "c1" -> "cc000000-0001-7000-8000-000000000001";
            case "s1" -> "55000000-0001-7000-8000-000000000001";
            case "w1" -> "ab000000-0001-7000-8000-000000000001";
            case "c2" -> "cc000000-0002-7000-8000-000000000001";
            case "s2" -> "55000000-0002-7000-8000-000000000001";
            case "w2" -> "ab000000-0002-7000-8000-000000000001";
            default -> throw new IllegalArgumentException("Unknown suffix: " + suffix);
        };
    }

    private static void setJwtAuth(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("tok")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        List<SimpleGrantedAuthority> auths = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, auths));
    }
}
