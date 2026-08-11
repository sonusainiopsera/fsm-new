package com.fieldservice.workorder.api;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests asserting that business precondition guards refuse transitions
 * with HTTP 422, the specific guard sub-code, and no persisted state change.
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
class GuardedTransitionIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_guard_test")
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

    // IDs shared across tests
    private UUID custId;
    private UUID siteId;
    private UUID userId;
    private UUID techId;

    // Seed part id from V4 reference data
    private static final UUID PART_ID = UUID.fromString("ffffffff-0000-7007-8000-000000000001");

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        custId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        userId = UUID.randomUUID();
        techId = UUID.randomUUID();

        tx.execute(s -> {
            entityManager.createNativeQuery(
                    "INSERT INTO customer (id, name) VALUES (?1, ?2)")
                    .setParameter(1, custId.toString())
                    .setParameter(2, "Guard Test Corp")
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "INSERT INTO site (id, name, customer_id) VALUES (?1, ?2, ?3)")
                    .setParameter(1, siteId.toString())
                    .setParameter(2, "Guard Test Site")
                    .setParameter(3, custId.toString())
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "INSERT INTO app_user (id, email, password_hash, full_name, active, version) " +
                    "VALUES (?1, ?2, '$2a$10$hash', 'Guard Tech', TRUE, 0)")
                    .setParameter(1, userId.toString())
                    .setParameter(2, "guardtech-" + techId + "@example.com")
                    .executeUpdate();
            entityManager.createNativeQuery(
                    "INSERT INTO technician (id, user_id, full_name, version) VALUES (?1, ?2, 'Guard Tech', 0)")
                    .setParameter(1, techId.toString())
                    .setParameter(2, userId.toString())
                    .executeUpdate();
            return null;
        });
    }

    // ---- LabourTimeRecordedGuard ------------------------------------------------

    @Test
    @DisplayName("COMPLETE without labour entries returns 422 with LABOUR_TIME_MISSING sub-code")
    void complete_without_labour_returns_422() throws Exception {
        UUID woId = createWorkOrderInState("IN_PROGRESS");
        Integer version = getVersion(woId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin").claim("roles", java.util.List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"COMPLETE","expectedVersion":%d}
                                """.formatted(version)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("guardSubCode"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("LABOUR_TIME_MISSING"));

        // State must be unchanged
        assertThat(getState(woId)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("COMPLETE with labour entry returns 200")
    void complete_with_labour_returns_200() throws Exception {
        UUID woId = createWorkOrderInState("IN_PROGRESS");
        insertLabourEntry(woId);
        Integer version = getVersion(woId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin").claim("roles", java.util.List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"COMPLETE","expectedVersion":%d}
                                """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("COMPLETED"));
    }

    // ---- PartsReconciledGuard --------------------------------------------------

    @Test
    @DisplayName("CLOSE with unreconciled parts returns 422 with PARTS_UNRECONCILED sub-code")
    void close_with_unreconciled_parts_returns_422() throws Exception {
        UUID woId = createWorkOrderInState("COMPLETED");
        insertUnreconciledPart(woId);
        Integer version = getVersion(woId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin").claim("roles", java.util.List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"CLOSE","expectedVersion":%d}
                                """.formatted(version)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("PARTS_UNRECONCILED"));

        assertThat(getState(woId)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("CLOSE with no parts consumed returns 200")
    void close_with_no_parts_returns_200() throws Exception {
        UUID woId = createWorkOrderInState("COMPLETED");
        Integer version = getVersion(woId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin").claim("roles", java.util.List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"CLOSE","expectedVersion":%d}
                                """.formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("CLOSED"));
    }

    // ---- CertificationCurrencyGuard --------------------------------------------

    @Test
    @DisplayName("ASSIGN to technician with expired certification returns 422 with CERTIFICATION_EXPIRED sub-code")
    void assign_expired_cert_returns_422() throws Exception {
        UUID woId = createWorkOrderInState("NEW");
        requireCompetency(woId, "ELECTRICAL_SAFETY");
        insertExpiredCert(techId, "ELECTRICAL_SAFETY");
        Integer version = getVersion(woId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher").claim("roles", java.util.List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":%d,"technicianId":"%s"}
                                """.formatted(version, techId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("CERTIFICATION_EXPIRED"));

        assertThat(getState(woId)).isEqualTo("NEW");
    }

    @Test
    @DisplayName("ASSIGN to technician with missing certification returns 422 with CERTIFICATION_MISSING sub-code")
    void assign_missing_cert_returns_422() throws Exception {
        UUID woId = createWorkOrderInState("NEW");
        requireCompetency(woId, "ELECTRICAL_SAFETY");
        // No certifications inserted
        Integer version = getVersion(woId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher").claim("roles", java.util.List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":%d,"technicianId":"%s"}
                                """.formatted(version, techId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("CERTIFICATION_MISSING"));
    }

    @Test
    @DisplayName("ASSIGN is refused even for ADMIN when certification is expired (no bypass)")
    void assign_expired_cert_refused_for_admin() throws Exception {
        UUID woId = createWorkOrderInState("NEW");
        requireCompetency(woId, "ELECTRICAL_SAFETY");
        insertExpiredCert(techId, "ELECTRICAL_SAFETY");
        Integer version = getVersion(woId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(t -> t.subject("admin").claim("roles", java.util.List.of("ADMIN"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":%d,"technicianId":"%s","reason":"emergency override"}
                                """.formatted(version, techId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_GUARD_REFUSED"));
    }

    @Test
    @DisplayName("ASSIGN to technician with valid certification returns 200")
    void assign_valid_cert_returns_200() throws Exception {
        UUID woId = createWorkOrderInState("NEW");
        requireCompetency(woId, "ELECTRICAL_SAFETY");
        insertValidCert(techId, "ELECTRICAL_SAFETY");
        Integer version = getVersion(woId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/transitions", woId)
                        .with(jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(t -> t.subject("dispatcher").claim("roles", java.util.List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"event":"ASSIGN","expectedVersion":%d,"technicianId":"%s"}
                                """.formatted(version, techId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("ASSIGNED"));
    }

    // ---- Helpers ---------------------------------------------------------------

    private UUID createWorkOrderInState(String state) {
        UUID woId = UUID.randomUUID();
        tx.execute(s -> {
            entityManager.createNativeQuery(
                    "INSERT INTO work_order (id, reference, state, priority, site_id, version) " +
                    "VALUES (?1, ?2, ?3, 'MEDIUM', ?4, 0)")
                    .setParameter(1, woId.toString())
                    .setParameter(2, "GT-" + woId.toString().substring(0, 8))
                    .setParameter(3, state)
                    .setParameter(4, siteId.toString())
                    .executeUpdate();
            return null;
        });
        return woId;
    }

    private Integer getVersion(UUID woId) {
        return tx.execute(s -> (Integer) entityManager
                .createNativeQuery("SELECT version FROM work_order WHERE id = ?1")
                .setParameter(1, woId.toString())
                .getSingleResult());
    }

    private String getState(UUID woId) {
        return tx.execute(s -> (String) entityManager
                .createNativeQuery("SELECT state FROM work_order WHERE id = ?1")
                .setParameter(1, woId.toString())
                .getSingleResult());
    }

    private void insertLabourEntry(UUID woId) {
        tx.execute(s -> {
            entityManager.createNativeQuery(
                    "INSERT INTO work_order_labour_entry (id, work_order_id, technician_id, minutes) " +
                    "VALUES (?1, ?2, ?3, 60)")
                    .setParameter(1, UUID.randomUUID().toString())
                    .setParameter(2, woId.toString())
                    .setParameter(3, techId.toString())
                    .executeUpdate();
            return null;
        });
    }

    private void insertUnreconciledPart(UUID woId) {
        tx.execute(s -> {
            entityManager.createNativeQuery(
                    "INSERT INTO work_order_parts_consumption (id, work_order_id, part_id, quantity, reconciled) " +
                    "VALUES (?1, ?2, ?3, 1, FALSE)")
                    .setParameter(1, UUID.randomUUID().toString())
                    .setParameter(2, woId.toString())
                    .setParameter(3, PART_ID.toString())
                    .executeUpdate();
            return null;
        });
    }

    private void requireCompetency(UUID woId, String certCode) {
        tx.execute(s -> {
            entityManager.createNativeQuery(
                    "INSERT INTO work_order_required_competency (id, work_order_id, certification_code) " +
                    "VALUES (?1, ?2, ?3)")
                    .setParameter(1, UUID.randomUUID().toString())
                    .setParameter(2, woId.toString())
                    .setParameter(3, certCode)
                    .executeUpdate();
            return null;
        });
    }

    private void insertExpiredCert(UUID techId, String certCode) {
        tx.execute(s -> {
            entityManager.createNativeQuery(
                    "INSERT INTO technician_certification (id, technician_id, certification_code, issued_at, expires_at) " +
                    "VALUES (?1, ?2, ?3, ?4, ?5)")
                    .setParameter(1, UUID.randomUUID().toString())
                    .setParameter(2, techId.toString())
                    .setParameter(3, certCode)
                    .setParameter(4, Instant.parse("2020-01-01T00:00:00Z"))
                    .setParameter(5, Instant.parse("2020-12-31T00:00:00Z")) // expired
                    .executeUpdate();
            return null;
        });
    }

    private void insertValidCert(UUID techId, String certCode) {
        tx.execute(s -> {
            entityManager.createNativeQuery(
                    "INSERT INTO technician_certification (id, technician_id, certification_code, issued_at, expires_at) " +
                    "VALUES (?1, ?2, ?3, ?4, ?5)")
                    .setParameter(1, UUID.randomUUID().toString())
                    .setParameter(2, techId.toString())
                    .setParameter(3, certCode)
                    .setParameter(4, Instant.parse("2025-01-01T00:00:00Z"))
                    .setParameter(5, Instant.parse("2030-12-31T00:00:00Z")) // valid until 2030
                    .executeUpdate();
            return null;
        });
    }
}
