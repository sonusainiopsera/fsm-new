package com.fieldservice.workorder.api;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.site.domain.Site;
import com.fieldservice.workorder.domain.WorkOrder;
import com.fieldservice.workorder.domain.WorkOrderStatus;
import com.fieldservice.workorder.holds.WorkOrderHold;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System integration tests for the work order history API.
 *
 * <p>Drives a work order through creation → assignment → hold → resume → completion → closure,
 * then asserts that the revision list and derived timeline match the actions taken.
 * Also asserts per-role scoping and masking behaviour.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class WorkOrderHistoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_history_it")
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
    private UUID technicianId;
    private UUID customerAccountId;

    /**
     * Seeds a work order through the full lifecycle:
     * NEW → ASSIGNED → ON_HOLD → IN_PROGRESS → COMPLETED → CLOSED.
     */
    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        technicianId     = UUID.randomUUID();
        customerAccountId = UUID.randomUUID();

        // Create: customer + site + work order (rev 1)
        workOrderId = tx.execute(status -> {
            entityManager.createNativeQuery(
                    "INSERT INTO customer (id, name) VALUES (?1, ?2)")
                    .setParameter(1, customerAccountId.toString())
                    .setParameter(2, "History IT Corp")
                    .executeUpdate();
            UUID siteId = UUID.randomUUID();
            entityManager.createNativeQuery(
                    "INSERT INTO site (id, name, customer_id) VALUES (?1, ?2, ?3)")
                    .setParameter(1, siteId.toString())
                    .setParameter(2, "IT Site A")
                    .setParameter(3, customerAccountId.toString())
                    .executeUpdate();
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-HIT-" + System.nanoTime(),
                    WorkOrderStatus.NEW, "MEDIUM", site, null);
            entityManager.persist(wo);
            return wo.getId();
        });

        // Assign technician (rev 2)
        tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, workOrderId);
            wo.assignTechnician(technicianId);
            return null;
        });

        // Transition to ON_HOLD (rev 3) and persist a hold record
        tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, workOrderId);
            wo.applyStateTransition(WorkOrderStatus.ON_HOLD);
            WorkOrderHold hold = new WorkOrderHold(
                    workOrderId, "AWAITING_PARTS", null, Instant.now(), technicianId);
            entityManager.persist(hold);
            return null;
        });

        // Resume: IN_PROGRESS (rev 4)
        tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, workOrderId);
            wo.applyStateTransition(WorkOrderStatus.IN_PROGRESS);
            return null;
        });

        // Complete (rev 5)
        tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, workOrderId);
            wo.applyStateTransition(WorkOrderStatus.COMPLETED);
            return null;
        });

        // Close (rev 6)
        tx.execute(status -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, workOrderId);
            wo.applyStateTransition(WorkOrderStatus.CLOSED);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Revision list tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("full lifecycle produces 6 revisions — ordered newest first")
    void full_lifecycle_has_six_revisions() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(6))
                .andExpect(jsonPath("$.data[0].revisionType").value("MOD"))
                .andExpect(jsonPath("$.data[5].revisionType").value("ADD"));
    }

    @Test
    @DisplayName("revision entries contain allow-listed field diffs with before and after values")
    void revision_entries_contain_field_diffs() throws Exception {
        // Page 0 size 50 — most recent revision (CLOSED transition) should diff state
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .param("size", "50")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                // Most recent change is COMPLETED→CLOSED
                .andExpect(jsonPath("$.data[0].changes[0].field").value("state"))
                .andExpect(jsonPath("$.data[0].changes[0].before").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].changes[0].after").value("CLOSED"));
    }

    @Test
    @DisplayName("ADD revision has null before and non-null after in changes")
    void add_revision_has_null_before() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .param("page", "0")
                        .param("size", "6")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                // Last in the page is the ADD revision
                .andExpect(jsonPath("$.data[5].revisionType").value("ADD"))
                .andExpect(jsonPath("$.data[5].changes[0].before").isEmpty());
    }

    @Test
    @DisplayName("revision entry exposes actorDisplayName not internal userId")
    void revision_does_not_expose_user_id() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actorDisplayName").isString())
                .andExpect(jsonPath("$.data[0].actorUserId").doesNotExist());
    }

    @Test
    @DisplayName("paginating revisions is deterministic — page 0 size 3 plus page 1 size 3 covers all")
    void pagination_deterministic() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .param("page", "0").param("size", "3")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.page.totalElements").value(6))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .param("page", "1").param("size", "3")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    // -------------------------------------------------------------------------
    // Timeline tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("timeline has 5 events in order: CLOSED, COMPLETED, RESUMED, HELD, ASSIGNED, CREATED")
    void full_lifecycle_timeline() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", workOrderId)
                        .param("size", "50")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventType").value("CLOSED"))
                .andExpect(jsonPath("$.data[1].eventType").value("COMPLETED"))
                .andExpect(jsonPath("$.data[2].eventType").value("RESUMED"))
                .andExpect(jsonPath("$.data[3].eventType").value("HELD"))
                .andExpect(jsonPath("$.data[4].eventType").value("ASSIGNED"))
                .andExpect(jsonPath("$.data[5].eventType").value("CREATED"));
    }

    @Test
    @DisplayName("HELD event carries holdReasonCode in detail map")
    void held_event_has_hold_reason() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", workOrderId)
                        .param("size", "50")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[3].detail.holdReasonCode").value("AWAITING_PARTS"));
    }

    @Test
    @DisplayName("timeline events carry actorDisplayName")
    void timeline_events_have_actor_display_name() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", workOrderId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actorDisplayName").isString());
    }

    // -------------------------------------------------------------------------
    // Role scoping and masking
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CUSTOMER can access timeline — role=CUSTOMER with matching customerAccountId")
    void customer_can_access_timeline() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(j -> j.claim("customer_account_ids",
                                        java.util.List.of(customerAccountId.toString()))))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("CUSTOMER timeline uses 'Service Team' not technician identity")
    void customer_timeline_masks_technician_identity() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", workOrderId)
                        .param("size", "50")
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(j -> j.claim("customer_account_ids",
                                        java.util.List.of(customerAccountId.toString()))))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actorDisplayName").value("Service Team"));
    }

    @Test
    @DisplayName("CUSTOMER cannot access /revisions — 403")
    void customer_cannot_access_revisions() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/revisions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(j -> j.claim("customer_account_ids",
                                        java.util.List.of(customerAccountId.toString()))))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("out-of-scope CUSTOMER timeline returns 403 — no existence disclosure")
    void out_of_scope_customer_gets_403() throws Exception {
        UUID otherCustomerId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(j -> j.claim("customer_account_ids",
                                        java.util.List.of(otherCustomerId.toString()))))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN with matching assignment can access timeline")
    void technician_can_access_own_timeline() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))
                                .jwt(j -> j.claim("technician_id", technicianId.toString())))
                )
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("TECHNICIAN without assignment returns 403")
    void technician_without_assignment_gets_403() throws Exception {
        UUID otherTech = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))
                                .jwt(j -> j.claim("technician_id", otherTech.toString())))
                )
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("non-existent work order returns 403 — indistinguishable from out-of-scope")
    void nonexistent_work_order_returns_403() throws Exception {
        UUID bogusId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/work-orders/{id}/timeline", bogusId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))
                                .jwt(j -> j.claim("technician_id", technicianId.toString())))
                )
                .andExpect(status().isForbidden());
    }
}
