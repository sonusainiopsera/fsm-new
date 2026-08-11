package com.fieldservice.fixtures;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.customer.domain.CustomerAccount;
import com.fieldservice.identity.domain.AppRole;
import com.fieldservice.identity.domain.AppUser;
import com.fieldservice.identity.domain.AppUserRepository;
import com.fieldservice.identity.domain.RoleAssignment;
import com.fieldservice.inventory.domain.Part;
import com.fieldservice.inventory.domain.StockBalance;
import com.fieldservice.inventory.domain.StockLocation;
import com.fieldservice.inventory.repository.PartRepository;
import com.fieldservice.inventory.repository.StockBalanceRepository;
import com.fieldservice.inventory.repository.StockLocationRepository;
import com.fieldservice.site.domain.Site;
import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.domain.TechnicianCertification;
import com.fieldservice.workorder.domain.Assignment;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.repository.WorkOrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persists the full fixture graph through the JPA layer and asserts every
 * foreign-key, CHECK constraint, and NOT NULL constraint is satisfied.
 *
 * <p>Uses a real PostgreSQL 16 instance via Testcontainers so constraint
 * semantics match production exactly.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@Import(TestSecurityConfig.class)
class FixturePersistenceIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_fixture_test")
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
        registry.add("spring.jpa.hibernate.ddl-auto",          () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired DataSource                 dataSource;
    @Autowired EntityManager              entityManager;
    @Autowired PlatformTransactionManager txManager;
    @Autowired AppUserRepository          appUserRepo;
    @Autowired WorkOrderRepository        workOrderRepo;
    @Autowired PartRepository             partRepo;
    @Autowired StockLocationRepository    stockLocationRepo;
    @Autowired StockBalanceRepository     stockBalanceRepo;

    @Test
    void dispatchReadyScenario_persistsWithoutConstraintViolations() {
        DeterministicIds.reset();
        ScenarioFixtures.DispatchReadyScenario scenario = ScenarioFixtures.dispatchReady();

        new TransactionTemplate(txManager).execute(status -> {
            // Users + roles
            for (int i = 0; i < scenario.users().size(); i++) {
                AppUser user = scenario.users().get(i);
                appUserRepo.save(user);
                entityManager.persist(scenario.roles().get(i));
            }

            // Customer + site
            entityManager.persist(scenario.customer());
            entityManager.persist(scenario.site());

            // Technicians + certifications
            for (int i = 0; i < scenario.technicians().size(); i++) {
                entityManager.persist(scenario.technicians().get(i));
                entityManager.persist(scenario.certifications().get(i));
            }

            // Inventory
            partRepo.save(scenario.part());
            stockLocationRepo.save(scenario.warehouse());
            stockBalanceRepo.save(scenario.stockBalance());

            // Work order
            workOrderRepo.save(scenario.workOrder());

            entityManager.flush();
            return null;
        });

        // Verify rows persisted via repo
        assertThat(appUserRepo.findById(scenario.users().get(0).getId())).isPresent();
        assertThat(workOrderRepo.findById(scenario.workOrder().getId())).isPresent();
        assertThat(partRepo.findById(scenario.part().getId())).isPresent();
        assertThat(stockBalanceRepo.findById(scenario.stockBalance().getId())).isPresent();
    }

    @Test
    void completedWorkOrder_hasAssignmentWithReleasedAt() {
        DeterministicIds.reset();
        ScenarioFixtures.FullLifecycleScenario lifecycle = ScenarioFixtures.fullLifecycle();

        new TransactionTemplate(txManager).execute(status -> {
            appUserRepo.save(lifecycle.user());
            entityManager.persist(lifecycle.role());
            entityManager.persist(lifecycle.customer());
            entityManager.persist(lifecycle.site());
            entityManager.persist(lifecycle.technician());

            for (WorkOrderFixtures.WorkOrderGraph g : lifecycle.workOrders()) {
                workOrderRepo.save(g.workOrder());
                if (g.hasAssignment()) {
                    entityManager.persist(g.assignment());
                }
            }
            entityManager.flush();
            return null;
        });

        // Verify COMPLETED work order
        WorkOrderFixtures.WorkOrderGraph completed = lifecycle.workOrders().stream()
                .filter(g -> g.workOrder().getState() == WorkOrderStatus.COMPLETED)
                .findFirst().orElseThrow();

        assertThat(workOrderRepo.findById(completed.workOrder().getId())).isPresent();
        assertThat(completed.assignment().getReleasedAt()).isNotNull();
    }

    @Test
    void seedCoreScript_isIdempotentOnDoubleExecution() throws Exception {
        String sql = new String(getClass()
                .getResourceAsStream("/fixtures/seed-core.sql").readAllBytes());

        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
                // Second execution must not fail (ON CONFLICT DO NOTHING)
                stmt.execute(sql);
            }
        }

        // Row count is stable after second execution
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM sla_policy WHERE id::text LIKE '00000000-0000-7011%'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            long count = rs.getLong(1);
            // Re-run also stable
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
            try (PreparedStatement ps2 = conn.prepareStatement(
                     "SELECT COUNT(*) FROM sla_policy WHERE id::text LIKE '00000000-0000-7011%'");
                 ResultSet rs2 = ps2.executeQuery()) {
                rs2.next();
                assertThat(rs2.getLong(1)).isEqualTo(count);
            }
        }
    }
}
