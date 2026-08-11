package com.fieldservice.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data-driven access-control matrix integration test (WO-203, AC-1 through AC-9).
 *
 * <p>For each cell in {@link AccessControlMatrix} (endpoint × role), mints a real RS256
 * JWT via {@link TestTokenMinter} backed by {@link TestRsaKeyPair}, sends the request
 * through the production security filter chain, and asserts:
 * <ul>
 *   <li>Unauthenticated requests → 401 with {@code "UNAUTHENTICATED"} code (AC-3)</li>
 *   <li>Forbidden roles → 403 with structured error body, no existence disclosure (AC-4)</li>
 *   <li>Allowed roles → not 401 or 403 (AC-1)</li>
 * </ul>
 *
 * <p>The test uses a Testcontainers PostgreSQL + Redis instance and the production
 * {@link SecurityFilterChainTestConfig} JwtDecoder so tokens are validated cryptographically.
 * No production security configuration is weakened or bypassed (AC constraint).
 *
 * <h3>Existence non-disclosure</h3>
 * For each row-scoped endpoint that returns 403 for a forbidden role, the test also issues
 * the same request with a random nonexistent ID and asserts the status codes are identical,
 * proving the response does not reveal whether the target record exists (AC-4, AC-5).
 *
 * <h3>Mass-assignment</h3>
 * For POST/PUT endpoints, the test also sends a body containing non-writable fields
 * ({@code id}, {@code createdAt}) and asserts 400 with no 2xx, proving the application
 * uses strict JSON deserialization (AC-9).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("login-test")
@Import(SecurityFilterChainTestConfig.class)
@Testcontainers
class AccessControlMatrixTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fieldservice_acm_test")
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

    private static final TestTokenMinter MINTER = TestTokenMinter.primary();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mockMvc;

    // ─── Argument provider ────────────────────────────────────────────────────

    /**
     * Expands the matrix cross-product: for each non-permit-all entry, produces one
     * {@link Arguments} tuple per role (including UNAUTHENTICATED).
     */
    static Stream<Arguments> matrixCells() {
        return AccessControlMatrix.entries().stream()
                .filter(e -> !e.permitAll())
                .flatMap(entry -> entry.expectedStatuses().entrySet().stream()
                        .map(roleStatus -> Arguments.of(
                                entry.id() + " as " + roleStatus.getKey(),
                                entry,
                                roleStatus.getKey(),
                                roleStatus.getValue()
                        )));
    }

    // ─── Matrix test ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("matrixCells")
    @DisplayName("Access control matrix: every endpoint × role × expected status")
    void matrixCell_assertsExpectedHttpStatus(
            String displayName,
            AccessControlMatrix.MatrixEntry entry,
            String role,
            int expectedStatus) throws Exception {

        ResultActions result = perform(entry, role);

        if (expectedStatus == 401) {
            // AC-3: Unauthenticated must return exactly 401 with structured error
            result.andExpect(status().isUnauthorized())
                  .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                  .andExpect(jsonPath("$.message").isString())
                  .andExpect(jsonPath("$.traceId").isString());

        } else if (expectedStatus == 403) {
            // AC-4: Forbidden must return exactly 403 with structured error
            result.andExpect(status().isForbidden())
                  .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                  .andExpect(jsonPath("$.message").isString())
                  .andExpect(jsonPath("$.traceId").isString());

        } else {
            // Allowed role: must NOT return 401 or 403. Any other status (200, 201, 400, 404, 409)
            // is acceptable because the test payload may not satisfy business logic
            // but the role is authorized to reach the service layer.
            result.andExpect(r -> {
                int actual = r.getResponse().getStatus();
                if (actual == 401) {
                    throw new AssertionError(
                            "Role " + role + " should be ALLOWED on '" + entry.id()
                                    + "' but received 401 UNAUTHORIZED. "
                                    + "Check the endpoint's @PreAuthorize or the security filter chain.");
                }
                if (actual == 403) {
                    throw new AssertionError(
                            "Role " + role + " should be ALLOWED on '" + entry.id()
                                    + "' but received 403 FORBIDDEN. "
                                    + "Check the @PreAuthorize annotation and the RBAC matrix.");
                }
            });
        }
    }

    // ─── Existence non-disclosure test ────────────────────────────────────────

    /**
     * Expands matrix entries for paths containing {@code {id}} where a role sees 403.
     * Each tuple: (displayName, entry, role, in-scope-existing-path, nonexistent-path)
     */
    static Stream<Arguments> disclosureCells() {
        return AccessControlMatrix.entries().stream()
                .filter(e -> !e.permitAll())
                .filter(e -> e.pathTemplate().contains("{id}") || e.pathTemplate().contains("{workOrderId}"))
                .flatMap(entry -> entry.expectedStatuses().entrySet().stream()
                        .filter(rs -> rs.getValue() == 403)
                        .map(rs -> {
                            String pathWithExisting = entry.resolvedPath().get();
                            String pathWithNonExistent = replaceFinalId(
                                    entry.pathTemplate(),
                                    entry.resolvedPath().get(),
                                    UUID.randomUUID().toString());
                            return Arguments.of(
                                    entry.id() + " as " + rs.getKey() + " — existence disclosure",
                                    entry,
                                    rs.getKey(),
                                    pathWithExisting,
                                    pathWithNonExistent
                            );
                        }));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("disclosureCells")
    @DisplayName("Non-disclosure: forbidden response is identical for existing and nonexistent IDs")
    void forbiddenResponse_isIdentical_forExistingAndNonexistent(
            String displayName,
            AccessControlMatrix.MatrixEntry entry,
            String role,
            String existingPath,
            String nonexistentPath) throws Exception {

        String token = mintToken(role);

        int statusForExisting = performWithPath(entry, existingPath, token)
                .andReturn().getResponse().getStatus();
        int statusForNonExistent = performWithPath(entry, nonexistentPath, token)
                .andReturn().getResponse().getStatus();

        assertThat(statusForExisting)
                .as("Status for existing (out-of-scope) and nonexistent IDs must be identical")
                .isEqualTo(statusForNonExistent);
    }

    // ─── Mass-assignment test ────────────────────────────────────────────────

    /**
     * POST/PUT cells for allowed roles — sends extra non-writable fields in the body.
     */
    static Stream<Arguments> massAssignmentCells() {
        return AccessControlMatrix.entries().stream()
                .filter(e -> !e.permitAll())
                .filter(e -> "POST".equals(e.httpMethod()) || "PUT".equals(e.httpMethod()))
                .filter(e -> !e.bodySupplier().get().isBlank())
                .flatMap(entry -> entry.expectedStatuses().entrySet().stream()
                        .filter(rs -> rs.getValue() < 400) // only test allowed roles
                        .limit(1) // one role is sufficient to test the binding
                        .map(rs -> Arguments.of(
                                entry.id() + " mass-assignment check",
                                entry,
                                rs.getKey()
                        )));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("massAssignmentCells")
    @DisplayName("Mass-assignment: non-writable fields in request body must be rejected (400)")
    void massAssignment_withNonWritableFields_isRejected(
            String displayName,
            AccessControlMatrix.MatrixEntry entry,
            String role) throws Exception {

        String token = mintToken(role);

        // Inject non-writable fields into the body
        String originalBody = entry.bodySupplier().get();
        String injectedBody = injectNonWritableFields(originalBody);

        if (injectedBody.equals(originalBody)) {
            // Body was not valid JSON or injection produced no change — skip silently
            return;
        }

        ResultActions result = performWithBodyAndToken(entry, entry.resolvedPath().get(),
                injectedBody, token);

        int status = result.andReturn().getResponse().getStatus();
        // Must be 400 (FAIL_ON_UNKNOWN_PROPERTIES) — not a 2xx that silently accepted the injected fields
        assertThat(status)
                .as("Submitting non-writable fields (%s) must return 400, not a 2xx that accepted them",
                        displayName)
                .isEqualTo(400);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ResultActions perform(AccessControlMatrix.MatrixEntry entry, String role)
            throws Exception {
        String path = entry.resolvedPath().get();
        String token = mintToken(role);
        return performWithPath(entry, path, token);
    }

    private ResultActions performWithPath(AccessControlMatrix.MatrixEntry entry,
                                          String path, String token) throws Exception {
        String body = entry.bodySupplier().get();
        return performWithBodyAndToken(entry, path, body, token);
    }

    private ResultActions performWithBodyAndToken(AccessControlMatrix.MatrixEntry entry,
                                                  String path, String body,
                                                  String token) throws Exception {
        var builder = switch (entry.httpMethod().toUpperCase()) {
            case "GET" -> get(path);
            case "POST" -> post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
            case "PUT" -> put(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("Unsupported method: " + entry.httpMethod());
        };

        if (token != null) {
            builder = builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        return mockMvc.perform(builder);
    }

    /** Mints an RS256 token for the given role, or returns null for UNAUTHENTICATED. */
    private static String mintToken(String role) {
        if ("UNAUTHENTICATED".equals(role)) return null;
        UUID subject = switch (role) {
            case "ADMIN"      -> TestJwtFactory.ADMIN_USER_ID;
            case "DISPATCHER" -> TestJwtFactory.DISPATCHER_USER_ID;
            case "MANAGER"    -> TestJwtFactory.MANAGER_USER_ID;
            case "TECHNICIAN" -> TestJwtFactory.TECH_1_USER_ID;
            case "CUSTOMER"   -> TestJwtFactory.CUSTOMER_USER_ID;
            default -> throw new IllegalArgumentException("Unknown role: " + role);
        };
        Map<String, Object> extras = switch (role) {
            case "TECHNICIAN" -> Map.of("technicianId", TestJwtFactory.TECH_1_ID.toString());
            case "CUSTOMER"   -> Map.of("customerAccountIds",
                    List.of(TestJwtFactory.ACCT_A.toString()));
            default -> Map.of();
        };
        return MINTER.validWithClaims(subject, List.of(role), extras);
    }

    /**
     * Replaces the final UUID segment in a resolved path with the given newId.
     * Used to create nonexistent-ID paths for non-disclosure probes.
     */
    private static String replaceFinalId(String template, String resolvedPath, String newId) {
        // Find the last UUID-shaped segment and replace it
        return resolvedPath.replaceAll(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                newId);
    }

    /**
     * Injects {@code id} and {@code createdAt} fields into an existing JSON object body.
     * Returns the original string unchanged if the body is not a JSON object.
     */
    private static String injectNonWritableFields(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            if (!node.isObject()) return body;
            var obj = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            obj.put("id", UUID.randomUUID().toString());
            obj.put("createdAt", "2024-01-01T00:00:00Z");
            obj.put("version", 99);
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return body;
        }
    }
}
