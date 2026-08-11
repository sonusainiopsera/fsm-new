package com.fieldservice.workorder.api;

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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for {@code POST /api/v1/work-orders/{id}/transitions}.
 *
 * <p>Both the Spring Security {@code @PreAuthorize} authority and the JWT {@code roles}
 * claim must be set so that (a) method security passes and (b) the AccessScopeResolver
 * can build the correct row-scope predicate for the scoped repository query.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.autoconfigure.exclude=",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
        })
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class WorkOrderTransitionControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_transition_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",       postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("spring.flyway.url",           postgres::getJdbcUrl);
        registry.add("spring.flyway.user",          postgres::getUsername);
        registry.add("spring.flyway.password",      postgres::getPassword);
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

        workOrderId = tx.execute(s -> {
            UUID custId = UUID.randomUUID();
            entityManager.createNativeQuery(
                            "INSERT INTO customer (id, name) VALUES (?1, ?2)")
                    .setParameter(1, custId.toString())
                    .setParameter(2, "Transition Test Corp")
                    .executeUpdate();
            UUID siteId = UUID.randomUUID();
            entityManager.createNativeQuery(
                            "INSERT INTO site (id, name, customer_id) VALUES (?1, ?2, ?3)")
                    .setParameter(1, siteId.toString())
                    .setParameter(2, "Transition Test Site")
                    .setParameter(3, custId.toString())
                    .executeUpdate();
            Site site = entityManager.find(Site.class, siteId);
            WorkOrder wo = new WorkOrder("WO-TRANS-" + System.nanoTime(),
                    WorkOrderStatus.NEW, "HIGH", site, null);
            entityManager.persist(wo);
            return wo.getId();
        });
    }

    // ---- Happy path ------------------------------------------------------------

    @Test
    @DisplayName("DISPATCHER can ASSIGN a NEW work order and receives new state")
    void dispatcher_assigns_new_work_order() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher-user")
                                        .claim("roles", List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workOrderId").value(workOrderId.toString()))
                .andExpect(jsonPath("$.fromState").value("NEW"))
                .andExpect(jsonPath("$.toState").value("ASSIGNED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.legalNextEvents").isArray())
                .andExpect(jsonPath("$.occurredAt").value(notNullValue()));
    }

    @Test
    @DisplayName("ADMIN can walk the happy path ASSIGN then DEPART from same work order")
    void admin_can_assign_then_depart() throws Exception {
        // ASSIGN
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-user")
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("ASSIGNED"));

        // DEPART
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-user")
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"DEPART","expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromState").value("ASSIGNED"))
                .andExpect(jsonPath("$.toState").value("EN_ROUTE"))
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---- 409 Illegal transition -------------------------------------------------

    @Test
    @DisplayName("Illegal event NEW→COMPLETE returns 409 WORK_ORDER_ILLEGAL_TRANSITION with legalNextEvents")
    void illegal_transition_returns_409_with_legal_events() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher-user")
                                        .claim("roles", List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"COMPLETE","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ILLEGAL_TRANSITION"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("legalNextEvents")));
    }

    // ---- 409 Version conflict ---------------------------------------------------

    @Test
    @DisplayName("Stale expectedVersion returns 409 WORK_ORDER_VERSION_CONFLICT")
    void stale_version_returns_409() throws Exception {
        // First transition succeeds: version 0 → 1
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-user")
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0}
                                """))
                .andExpect(status().isOk());

        // Second request with stale expectedVersion=0 → 409 VERSION_CONFLICT
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-user")
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"UNASSIGN","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_VERSION_CONFLICT"));
    }

    // ---- 403 Cross-role ---------------------------------------------------------

    @Test
    @DisplayName("CUSTOMER cannot trigger any transition — 403 at method security level")
    void customer_role_is_forbidden_at_method_security_level() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .jwt(t -> t.subject("customer-user")
                                        .claim("roles", List.of("CUSTOMER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN cannot transition a work order not assigned to them — 403 via scope")
    void technician_cannot_transition_work_order_not_assigned_to_them() throws Exception {
        UUID otherTechnicianId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))
                                .jwt(t -> t.subject(otherTechnicianId.toString())
                                        .claim("technician_id", otherTechnicianId.toString())
                                        .claim("roles", List.of("TECHNICIAN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0}
                                """))
                .andExpect(status().isForbidden());
    }

    // ---- 400 Validation ---------------------------------------------------------

    @Test
    @DisplayName("Unknown event name returns 400")
    void unknown_event_name_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher-user")
                                        .claim("roles", List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"LAUNCH_ROCKET","expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Missing expectedVersion returns 400 with field-level error")
    void missing_expected_version_returns_400() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher-user")
                                        .claim("roles", List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("expectedVersion")));
    }

    @Test
    @DisplayName("HOLD event without holdReasonCode returns 400 validation error")
    void hold_event_without_hold_reason_code_returns_400() throws Exception {
        // Advance work order to IN_PROGRESS so HOLD would be legal (bypassing event validation)
        tx.execute(s -> {
            WorkOrder wo = entityManager.find(WorkOrder.class, workOrderId);
            wo.applyStateTransition(WorkOrderStatus.IN_PROGRESS);
            return null;
        });
        Integer version = tx.execute(s ->
                entityManager.find(WorkOrder.class, workOrderId).getVersion());

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-user")
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"HOLD","expectedVersion":%d}
                                """.formatted(version)))
                .andExpect(status().isBadRequest());
    }

    // ---- Idempotent replay ------------------------------------------------------

    @Test
    @DisplayName("Replaying the same Idempotency-Key returns the original response without re-applying the transition")
    void idempotency_key_replay_does_not_double_apply() throws Exception {
        String idempotencyKey = "idem-key-" + UUID.randomUUID().toString().replace("-", "");
        String body = """
                {"event":"ASSIGN","expectedVersion":0}
                """;

        // First request — succeeds and is cached by IdempotencyFilter
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-replay-user")
                                        .claim("roles", List.of("ADMIN"))))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("ASSIGNED"));

        // Second request with same Idempotency-Key — must replay cached response
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", workOrderId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-replay-user")
                                        .claim("roles", List.of("ADMIN"))))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("ASSIGNED"));

        // Work order version must be 1, not 2 (transition applied only once)
        Integer finalVersion = tx.execute(s ->
                entityManager.find(WorkOrder.class, workOrderId).getVersion());
        assertThat(finalVersion)
                .as("Version must be 1 after idempotent replay — transition was applied exactly once")
                .isEqualTo(1);
    }

    // ---- Unknown work order id --------------------------------------------------

    @Test
    @DisplayName("Non-existent work order id returns 403 (non-disclosure: same as out-of-scope)")
    void nonexistent_work_order_returns_403() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", UUID.randomUUID())
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin-user")
                                        .claim("roles", List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":0}
                                """))
                .andExpect(status().isForbidden());
    }
}
