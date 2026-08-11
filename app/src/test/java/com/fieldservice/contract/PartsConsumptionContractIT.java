package com.fieldservice.contract;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.contract.support.ApiAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static com.fieldservice.contract.support.ApiAssertions.assertErrorShape;
import static com.fieldservice.contract.support.ApiAssertions.assertNoInternals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 API contract tests for the parts-consumption endpoint group.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li><strong>AC1</strong>: End-to-end consumption request with response body shape assertion.</li>
 *   <li><strong>AC3</strong>: 422 insufficient-stock response carries uniform error envelope with
 *       field-level breakdown per failed line.</li>
 *   <li><strong>AC3</strong>: Error response leaves stock balance byte-identical (no partial write).</li>
 *   <li><strong>AC7</strong>: Idempotent replay returns the original response and causes exactly
 *       one {@code stock_ledger} decrement row.</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
        })
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class PartsConsumptionContractIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_parts_contract")
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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired MockMvc     mockMvc;
    @Autowired JdbcTemplate jdbc;

    // IDs seeded in @BeforeEach
    private String woId;
    private String locId;
    private String partId;
    private String dispUserId;

    @BeforeEach
    void seed() {
        String custId  = UUID.randomUUID().toString();
        String siteId  = UUID.randomUUID().toString();
        String userId  = UUID.randomUUID().toString();
        String techId  = UUID.randomUUID().toString();
        dispUserId     = UUID.randomUUID().toString();
        locId          = UUID.randomUUID().toString();
        partId         = UUID.randomUUID().toString();
        String balId   = UUID.randomUUID().toString();
        woId           = UUID.randomUUID().toString();

        jdbc.update("INSERT INTO customer (id, name) VALUES (?, ?)", custId, "Parts Corp");
        jdbc.update("INSERT INTO site (id, name, customer_id) VALUES (?, ?, ?)",
                siteId, "Parts Site", custId);
        jdbc.update("INSERT INTO app_user (id, email, password_hash, full_name, active) VALUES (?, ?, 'x', 'Tech', TRUE)", userId, "tech-" + userId + "@test.local");
        jdbc.update("INSERT INTO app_user (id, email, password_hash, full_name, active) VALUES (?, ?, 'x', 'Disp', TRUE)", dispUserId, "disp-" + dispUserId + "@test.local");
        jdbc.update("INSERT INTO technician (id, user_id, full_name) VALUES (?, ?, 'Tech')", techId, userId);
        jdbc.update("INSERT INTO stock_location (id, name, location_type, technician_id) VALUES (?, ?, 'VEHICLE', ?)",
                locId, "Van", techId);
        jdbc.update("INSERT INTO part (id, name, sku, unit_of_measure, active) VALUES (?, ?, ?, 'EACH', TRUE)",
                partId, "Test Widget", "SKU-" + partId.substring(0, 8));
        jdbc.update("INSERT INTO stock_balance (id, stock_location_id, part_id, quantity_on_hand) VALUES (?, ?, ?, 10)",
                balId, locId, partId);
        jdbc.update(
            "INSERT INTO work_order (id, reference, state, priority, site_id) VALUES (?, ?, 'ASSIGNED', 'HIGH', ?)",
            woId, "WO-PARTS-" + System.nanoTime(), siteId);
    }

    // -----------------------------------------------------------------------
    // AC1: Successful consumption — response body shape
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC1: Successful parts consumption returns workOrderId and loggedLines")
    void successful_consumption_returns_full_response_shape() throws Exception {
        String body = """
                {"stockLocationId":"%s","lines":[{"partId":"%s","quantity":3,"reasonCode":"INSTALL"}]}
                """.formatted(locId, partId);

        MvcResult result = mockMvc.perform(post("/api/v1/work-orders/{id}/parts", woId)
                        .with(dispatcherJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workOrderId").value(woId))
                .andExpect(jsonPath("$.loggedLines").isArray())
                .andExpect(jsonPath("$.loggedLines[0].quantity").value(3))
                .andExpect(jsonPath("$.loggedLines[0].resultingQuantityOnHand").value(7))
                .andReturn();

        assertNoInternals(result.getResponse());
    }

    // -----------------------------------------------------------------------
    // AC3: Insufficient stock — 422 with error envelope, balance unchanged
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: Consuming more than available returns 422 with error envelope, balance unchanged")
    void insufficient_stock_returns_422_with_error_envelope() throws Exception {
        String body = """
                {"stockLocationId":"%s","lines":[{"partId":"%s","quantity":999}]}
                """.formatted(locId, partId);

        MvcResult result = mockMvc.perform(post("/api/v1/work-orders/{id}/parts", woId)
                        .with(dispatcherJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertErrorShape(result.getResponse(), "INSUFFICIENT_STOCK");
        assertNoInternals(result.getResponse());

        // Balance must be unchanged (no partial write)
        Integer qty = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM stock_balance WHERE stock_location_id = ? AND part_id = ?",
                Integer.class, locId, partId);
        assertThat(qty)
                .as("Balance must be 10 after rejected consumption (no partial write)")
                .isEqualTo(10);
    }

    // -----------------------------------------------------------------------
    // AC7: Idempotent replay — exactly one stock_ledger row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC7: Idempotent replay returns the original response and creates exactly one stock_ledger row")
    void idempotent_replay_creates_exactly_one_ledger_row() throws Exception {
        String idempKey = "idem-parts-" + UUID.randomUUID().toString().replace("-", "");
        String body = """
                {"stockLocationId":"%s","lines":[{"partId":"%s","quantity":2,"reasonCode":"INSTALL"}]}
                """.formatted(locId, partId);

        // First request
        MvcResult first = mockMvc.perform(post("/api/v1/work-orders/{id}/parts", woId)
                        .with(dispatcherJwt())
                        .header("Idempotency-Key", idempKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // Replay with same key
        MvcResult replay = mockMvc.perform(post("/api/v1/work-orders/{id}/parts", woId)
                        .with(dispatcherJwt())
                        .header("Idempotency-Key", idempKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // Responses must be identical
        assertThat(replay.getResponse().getContentAsString())
                .as("Replayed response must match the original")
                .isEqualTo(first.getResponse().getContentAsString());

        // Exactly one stock_ledger row must exist (decrement applied once)
        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_ledger WHERE work_order_id = ? AND part_id = ?",
                Integer.class, woId, partId);
        assertThat(ledgerRows)
                .as("Idempotent replay must create exactly one stock_ledger decrement row")
                .isEqualTo(1);

        // Balance must reflect exactly one decrement (10 - 2 = 8)
        Integer qty = jdbc.queryForObject(
                "SELECT quantity_on_hand FROM stock_balance WHERE stock_location_id = ? AND part_id = ?",
                Integer.class, locId, partId);
        assertThat(qty)
                .as("Balance after idempotent replay must reflect only one decrement (10-2=8)")
                .isEqualTo(8);
    }

    // -----------------------------------------------------------------------
    // AC7: 24-hour idempotency key retention
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC7: Idempotency-Key record is persisted for later retention check")
    void idempotency_key_is_persisted() throws Exception {
        String idempKey = "idem-retention-" + UUID.randomUUID().toString().replace("-", "");
        String body = """
                {"stockLocationId":"%s","lines":[{"partId":"%s","quantity":1}]}
                """.formatted(locId, partId);

        mockMvc.perform(post("/api/v1/work-orders/{id}/parts", woId)
                        .with(dispatcherJwt())
                        .header("Idempotency-Key", idempKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // The idempotency key record must be in the database
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_key WHERE key_value = ?",
                Integer.class, idempKey);
        assertThat(keyCount)
                .as("Idempotency-Key record must be persisted in idempotency_key table")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.RequestPostProcessor dispatcherJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                .jwt(j -> j.subject(dispUserId).claim("roles", List.of("DISPATCHER")));
    }
}
