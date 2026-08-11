package com.fieldservice.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level probe matrix for row-scope enforcement (WO-113 AC-13).
 *
 * <p>For each combination of (principal role, work order, expectation), issues
 * GET /api/v1/work-orders/{id} with a fully signed RS256 JWT and asserts the correct
 * HTTP status per the ratified non-disclosure rule:
 * <ul>
 *   <li>200 — in-scope access</li>
 *   <li>403 FORBIDDEN — cross-role denial (tech accessing another tech's WO, manager probing)</li>
 *   <li>404 NOT_FOUND — cross-account customer denial (customer probing another account's WO)</li>
 * </ul>
 *
 * <p>Fixture topology (V100__test_fixtures.sql):
 * <pre>
 *   ACCT_A (00…001): wo_a1 → TECH_1, wo_a2 → TECH_2, wo_unassigned → null
 *   ACCT_B (00…002): wo_b1 → TECH_1
 * </pre>
 *
 * <p>Uses the production security filter chain with {@link SecurityFilterChainTestConfig}
 * (backed by {@link TestRsaKeyPair}) to exercise the full cryptographic JWT validation path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("login-test")
@Import(SecurityFilterChainTestConfig.class)
@Testcontainers
class AccessProbeMatrixTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fieldservice_probe_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.security.oauth2.resourceserver.jwt.jwks-uri",
                () -> "http://localhost:0/.well-known/jwks.json");
    }

    // Work order IDs from V100__test_fixtures.sql
    private static final UUID WO_A1 = TestJwtFactory.WO_A1; // ACCT_A, TECH_1
    private static final UUID WO_A2 = TestJwtFactory.WO_A2; // ACCT_A, TECH_2
    private static final UUID WO_B1 = TestJwtFactory.WO_B1; // ACCT_B, TECH_1

    private static final TestTokenMinter MINTER = TestTokenMinter.primary();

    @Autowired
    MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Probe matrix: role × work-order-id × expected HTTP status
    // -------------------------------------------------------------------------

    static Stream<Arguments> probeMatrix() {
        // DISPATCHER — permit-all, sees everything
        String dispatcherToken = MINTER.valid(TestJwtFactory.DISPATCHER_USER_ID, List.of("DISPATCHER"));
        // MANAGER — permit-all
        String managerToken = MINTER.valid(TestJwtFactory.MANAGER_USER_ID, List.of("MANAGER"));
        // ADMIN — permit-all
        String adminToken = MINTER.valid(TestJwtFactory.ADMIN_USER_ID, List.of("ADMIN"));
        // TECH_1 — scoped to their assigned work orders (wo_a1, wo_b1)
        String tech1Token = MINTER.validWithClaims(TestJwtFactory.TECH_1_USER_ID, List.of("TECHNICIAN"),
                Map.of("technicianId", TestJwtFactory.TECH_1_ID.toString()));
        // TECH_2 — scoped to their assigned work orders (wo_a2)
        String tech2Token = MINTER.validWithClaims(TestJwtFactory.TECH_2_USER_ID, List.of("TECHNICIAN"),
                Map.of("technicianId", TestJwtFactory.TECH_2_ID.toString()));
        // CUSTOMER for ACCT_A only
        UUID customerAUserId = TestJwtFactory.CUSTOMER_USER_ID;
        String customerAToken = MINTER.validWithClaims(customerAUserId, List.of("CUSTOMER"),
                Map.of("customerAccountIds", List.of(TestJwtFactory.ACCT_A.toString())));
        // CUSTOMER for ACCT_B only
        UUID customerBUserId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000022");
        String customerBToken = MINTER.validWithClaims(customerBUserId, List.of("CUSTOMER"),
                Map.of("customerAccountIds", List.of(TestJwtFactory.ACCT_B.toString())));

        return Stream.of(
                // --- DISPATCHER: permit-all ---
                Arguments.of("DISPATCHER", dispatcherToken, WO_A1, 200, "in-scope WO_A1"),
                Arguments.of("DISPATCHER", dispatcherToken, WO_B1, 200, "in-scope WO_B1"),

                // --- MANAGER: permit-all ---
                Arguments.of("MANAGER", managerToken, WO_A1, 200, "in-scope WO_A1"),
                Arguments.of("MANAGER", managerToken, WO_B1, 200, "in-scope WO_B1"),

                // --- ADMIN: permit-all ---
                Arguments.of("ADMIN", adminToken, WO_A1, 200, "in-scope WO_A1"),
                Arguments.of("ADMIN", adminToken, WO_B1, 200, "in-scope WO_B1"),

                // --- TECH_1: sees wo_a1 (assigned) and wo_b1 (assigned) ---
                Arguments.of("TECH_1", tech1Token, WO_A1, 200, "in-scope assigned WO"),
                Arguments.of("TECH_1", tech1Token, WO_B1, 200, "in-scope assigned WO"),
                Arguments.of("TECH_1", tech1Token, WO_A2, 403, "cross-role: WO assigned to TECH_2"),

                // --- TECH_2: sees wo_a2 (assigned), not wo_a1 or wo_b1 ---
                Arguments.of("TECH_2", tech2Token, WO_A2, 200, "in-scope assigned WO"),
                Arguments.of("TECH_2", tech2Token, WO_A1, 403, "cross-role: WO assigned to TECH_1"),
                Arguments.of("TECH_2", tech2Token, WO_B1, 403, "cross-role: WO in ACCT_B assigned to TECH_1"),

                // --- CUSTOMER_A: sees ACCT_A work orders, not ACCT_B ---
                Arguments.of("CUSTOMER_A", customerAToken, WO_A1, 200, "in-scope ACCT_A WO"),
                Arguments.of("CUSTOMER_A", customerAToken, WO_A2, 200, "in-scope ACCT_A WO"),
                Arguments.of("CUSTOMER_A", customerAToken, WO_B1, 404, "cross-account: WO_B1 belongs to ACCT_B"),

                // --- CUSTOMER_B: sees ACCT_B work orders, not ACCT_A ---
                Arguments.of("CUSTOMER_B", customerBToken, WO_B1, 200, "in-scope ACCT_B WO"),
                Arguments.of("CUSTOMER_B", customerBToken, WO_A1, 404, "cross-account: WO_A1 belongs to ACCT_A"),
                Arguments.of("CUSTOMER_B", customerBToken, WO_A2, 404, "cross-account: WO_A2 belongs to ACCT_A")
        );
    }

    @ParameterizedTest(name = "[{index}] {0} → {2} → HTTP {3}: {4}")
    @MethodSource("probeMatrix")
    @DisplayName("Scope probe matrix: role × work-order → expected HTTP status")
    void probeMatrix_assertsExpectedStatus(
            String roleLabel,
            String bearerToken,
            UUID workOrderId,
            int expectedStatus,
            String scenario) throws Exception {

        mockMvc.perform(get("/api/v1/work-orders/{id}", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken))
                .andExpect(status().is(expectedStatus));
    }

    @ParameterizedTest(name = "[{index}] {0} → {2} → 403/404 body non-disclosure")
    @MethodSource("probeMatrix")
    @DisplayName("Denied scope probes must not disclose resource existence in response body")
    void deniedProbes_neverDiscloseExistence(
            String roleLabel,
            String bearerToken,
            UUID workOrderId,
            int expectedStatus,
            String scenario) throws Exception {

        if (expectedStatus == 200) return; // only test denial paths

        mockMvc.perform(get("/api/v1/work-orders/{id}", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.traceId").exists())
                // Response must not contain the work order ID anywhere in the body
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    if (body.contains(workOrderId.toString())) {
                        throw new AssertionError(
                                "Response body disclosed work order ID " + workOrderId);
                    }
                });
    }

    @ParameterizedTest(name = "[{index}] CUSTOMER {0} cross-account → must return 404 not 403")
    @MethodSource("crossAccountCustomerProbes")
    @DisplayName("Customer cross-account denials must return 404 per the non-disclosure rule")
    void customerCrossAccount_returns404(
            String tokenLabel,
            String bearerToken,
            UUID workOrderId) throws Exception {

        mockMvc.perform(get("/api/v1/work-orders/{id}", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    static Stream<Arguments> crossAccountCustomerProbes() {
        String customerAToken = MINTER.validWithClaims(TestJwtFactory.CUSTOMER_USER_ID, List.of("CUSTOMER"),
                Map.of("customerAccountIds", List.of(TestJwtFactory.ACCT_A.toString())));
        String customerBToken = MINTER.validWithClaims(
                UUID.fromString("aaaaaaaa-0000-0000-0000-000000000022"), List.of("CUSTOMER"),
                Map.of("customerAccountIds", List.of(TestJwtFactory.ACCT_B.toString())));
        return Stream.of(
                Arguments.of("CUSTOMER_A cross to ACCT_B", customerAToken, WO_B1),
                Arguments.of("CUSTOMER_B cross to ACCT_A", customerBToken, WO_A1),
                Arguments.of("CUSTOMER_B cross to ACCT_A", customerBToken, WO_A2)
        );
    }
}
