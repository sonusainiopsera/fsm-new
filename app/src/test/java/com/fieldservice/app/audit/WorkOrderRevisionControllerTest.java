package com.fieldservice.app.audit;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for the work order revision endpoint.
 *
 * <p>Verifies pagination, descending ordering, and role-based access control.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class WorkOrderRevisionControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_ctrl_test")
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

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private UUID workOrderId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);

        // Seed a work order with two revisions (create + update)
        workOrderId = tx.execute(status -> {
            UUID custId = UUID.randomUUID();
            entityManager.createNativeQuery(
                    "INSERT INTO customer (id, name) VALUES (?1, ?2)")
                    .setParameter(1, custId.toString())
                    .setParameter(2, "Ctrl Test Corp")
                    .executeUpdate();
            UUID siteId = UUID.randomUUID();
            entityManager.createNativeQuery(
                    "INSERT INTO site (id, name, customer_id) VALUES (?1, ?2, ?3)")
                    .setParameter(1, siteId.toString())
                    .setParameter(2, "Ctrl Test Site")
                    .setParameter(3, custId.toString())
                    .executeUpdate();
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-CTRL-" + System.nanoTime(),
                    WorkOrderStatus.NEW, "HIGH", site, null);
            entityManager.persist(wo);
            return wo.getId();
        });

        tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, workOrderId);
            wo.assignTechnician(UUID.randomUUID());
            return null;
        });
    }

    @Test
    @DisplayName("ADMIN can retrieve revision history with pagination metadata")
    void admin_can_retrieve_revisions() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("MANAGER can retrieve revision history")
    void manager_can_retrieve_revisions() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("DISPATCHER is denied access to revision history")
    void dispatcher_denied_access() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN is denied access to revision history")
    void technician_denied_access() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("revisions are returned in descending revision order (most recent first)")
    void revisions_ordered_descending() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].revisionType").value("MOD"))
                .andExpect(jsonPath("$.content[1].revisionType").value("ADD"));
    }

    @Test
    @DisplayName("pagination page=0 size=1 returns only the most recent revision")
    void pagination_returns_single_item() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .param("page", "0")
                        .param("size", "1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].revisionType").value("MOD"));
    }

    @Test
    @DisplayName("unauthenticated request returns 401")
    void unauthenticated_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId))
                .andExpect(status().isUnauthorized());
    }
}
