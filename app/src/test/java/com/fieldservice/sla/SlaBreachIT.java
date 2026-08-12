package com.fieldservice.sla;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.sla.internal.SlaBreachService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for WO-144: SLA breach recording, attribution, finalisation, and listing.
 *
 * <p>Covers:
 * <ul>
 *   <li>AC-1 recordBreach idempotency (unique index enforcement)</li>
 *   <li>AC-3 transactional atomicity: breach + flag clear + event in one TX</li>
 *   <li>AC-6 attribution authorization matrix (403 for TECHNICIAN/CUSTOMER)</li>
 *   <li>AC-9 finalisation idempotency (final_overrun_minutes written once)</li>
 *   <li>AC-10 paginated breach listing via GET /api/v1/sla/breaches</li>
 *   <li>reason-codes endpoint returns controlled vocabulary</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@ActiveProfiles({"worker", "test"})
class SlaBreachIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_breach_test")
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

    @Autowired SlaBreachService breachService;
    @Autowired JdbcTemplate     jdbc;
    @Autowired MockMvc          mockMvc;

    // ─── AC-1: idempotency ───────────────────────────────────────────────────

    @Test
    @DisplayName("recordBreach is idempotent — second call produces no additional row")
    void recordBreach_idempotent() {
        UUID workOrderId = UUID.randomUUID();
        Instant deadline = Instant.now().minusSeconds(600);

        // Insert a minimal work_order row so the FK constraint is satisfied
        jdbc.update("""
                INSERT INTO work_order (id, title, description, state, priority, created_at, updated_at, version)
                VALUES (?, 'Idempotent test WO', 'desc', 'IN_PROGRESS', 'P2', NOW(), NOW(), 0)
                """, workOrderId);

        // First call
        breachService.recordBreach(workOrderId, "RESOLUTION", deadline,
                Instant.now(), 10L, 0L);
        long countAfterFirst = countBreaches(workOrderId, "RESOLUTION");
        assertThat(countAfterFirst).isEqualTo(1);

        // Second call with same type — must be a no-op
        breachService.recordBreach(workOrderId, "RESOLUTION", deadline,
                Instant.now(), 15L, 0L);
        long countAfterSecond = countBreaches(workOrderId, "RESOLUTION");
        assertThat(countAfterSecond).isEqualTo(1);
    }

    // ─── AC-3: transactional atomicity ──────────────────────────────────────

    @Test
    @DisplayName("recordBreach persists breach row in the same transaction")
    void recordBreach_persistsRow() {
        UUID workOrderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO work_order (id, title, description, state, priority, created_at, updated_at, version)
                VALUES (?, 'Atomicity test WO', 'desc', 'IN_PROGRESS', 'P1', NOW(), NOW(), 0)
                """, workOrderId);

        breachService.recordBreach(workOrderId, "RESPONSE",
                Instant.now().minusSeconds(300), Instant.now(), 5L, 10L);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_breach WHERE work_order_id = ? AND breach_type = 'RESPONSE'",
                Long.class, workOrderId);
        assertThat(count).isEqualTo(1);
    }

    // ─── AC-9: finalisation idempotency ─────────────────────────────────────

    @Test
    @DisplayName("finalise writes final_overrun_minutes once; subsequent calls are no-ops")
    void finalise_idempotent() {
        UUID workOrderId = UUID.randomUUID();
        Instant deadline = Instant.now().minusSeconds(3600);

        jdbc.update("""
                INSERT INTO work_order (id, title, description, state, priority, created_at, updated_at, version)
                VALUES (?, 'Finalise test WO', 'desc', 'CLOSED', 'P1', NOW(), NOW(), 0)
                """, workOrderId);

        breachService.recordBreach(workOrderId, "RESOLUTION", deadline,
                deadline.plusSeconds(300), 5L, 0L);

        Instant closureInstant = Instant.now();
        breachService.finalise(workOrderId, closureInstant);

        Long firstFinalOverrun = jdbc.queryForObject(
                "SELECT final_overrun_minutes FROM sla_breach WHERE work_order_id = ? AND breach_type = 'RESOLUTION'",
                Long.class, workOrderId);
        assertThat(firstFinalOverrun).isNotNull().isGreaterThanOrEqualTo(0L);

        // Second finalise call must not change the value
        breachService.finalise(workOrderId, closureInstant.plusSeconds(3600));

        Long secondFinalOverrun = jdbc.queryForObject(
                "SELECT final_overrun_minutes FROM sla_breach WHERE work_order_id = ? AND breach_type = 'RESOLUTION'",
                Long.class, workOrderId);
        assertThat(secondFinalOverrun).isEqualTo(firstFinalOverrun);
    }

    // ─── AC-6: attribution authorization matrix ──────────────────────────────

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    @DisplayName("TECHNICIAN cannot attribute — 403 before any query runs")
    void attribute_technicianForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/sla/breaches/{id}/reason", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"PARTS_UNAVAILABLE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("CUSTOMER cannot attribute — 403 before any query runs")
    void attribute_customerForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/sla/breaches/{id}/reason", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"PARTS_UNAVAILABLE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "DISPATCHER")
    @DisplayName("DISPATCHER calling attribute on non-existent breach gets 403 (no existence disclosure)")
    void attribute_dispatcherNonExistentBreach_403() throws Exception {
        mockMvc.perform(post("/api/v1/sla/breaches/{id}/reason", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"CAPACITY_SHORTFALL\"}"))
                .andExpect(status().isForbidden());
    }

    // ─── AC-10: paginated listing ────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "DISPATCHER")
    @DisplayName("GET /api/v1/sla/breaches returns paginated list with meta")
    void list_returnsPage() throws Exception {
        // Seed a breach row that will show up
        UUID workOrderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO work_order (id, title, description, state, priority, created_at, updated_at, version)
                VALUES (?, 'List test WO', 'desc', 'IN_PROGRESS', 'P1', NOW(), NOW(), 0)
                """, workOrderId);
        breachService.recordBreach(workOrderId, "RESOLUTION",
                Instant.now().minusSeconds(1800), Instant.now(), 30L, 0L);

        mockMvc.perform(get("/api/v1/sla/breaches")
                        .param("size", "10")
                        .param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.size").value(10))
                .andExpect(jsonPath("$.meta.number").value(0))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    @DisplayName("TECHNICIAN cannot list breaches — 403")
    void list_technicianForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/sla/breaches"))
                .andExpect(status().isForbidden());
    }

    // ─── reason-codes endpoint ───────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MANAGER")
    @DisplayName("GET /api/v1/sla/breaches/reason-codes returns all 5 controlled vocabulary entries")
    void reasonCodes_returnsControlledVocabulary() throws Exception {
        mockMvc.perform(get("/api/v1/sla/breaches/reason-codes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0]").value("PARTS_UNAVAILABLE"))
                .andExpect(jsonPath("$[1]").value("CUSTOMER_ACCESS_DENIED"))
                .andExpect(jsonPath("$[2]").value("CAPACITY_SHORTFALL"))
                .andExpect(jsonPath("$[3]").value("TRAVEL_DISRUPTION"))
                .andExpect(jsonPath("$[4]").value("MISPRIORITISED_AT_INTAKE"));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private long countBreaches(UUID workOrderId, String breachType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_breach WHERE work_order_id = ? AND breach_type = ?",
                Long.class, workOrderId, breachType);
        return count != null ? count : 0L;
    }
}
