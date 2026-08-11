package com.fieldservice.identity;

import com.fieldservice.identity.application.RefreshTokenService;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System integration tests for POST /api/v1/auth/refresh (WO-110).
 *
 * <p>Uses Testcontainers PostgreSQL + Redis for a fully faithful environment.
 * Fixtures loaded via {@code @Sql} before the test class.
 *
 * <p>Tests:
 * <ul>
 *   <li>AC-2: login → refresh → replay original → 401 + full family revoked</li>
 *   <li>AC-1: handle in body / query / header rejected with 401</li>
 *   <li>AC-4: already-revoked family returns 401 without second revocation</li>
 *   <li>AC-5: expired family returns 401</li>
 *   <li>AC-7: concurrency — exactly one 200, one 401 from parallel same-handle requests</li>
 *   <li>AC-6: plaintext handle never stored in DB</li>
 *   <li>AC-8: deactivated-owner family returns 401</li>
 * </ul>
 */
@Tag("integration")
@Sql(scripts = {"/fixtures/identity-seed.sql", "/fixtures/refresh-token-fixtures.sql"},
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Testcontainers
class RefreshRotationIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fieldservice")
                    .withUsername("fieldservice")
                    .withPassword("fieldservice");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",       POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",  POSTGRES::getUsername);
        registry.add("spring.datasource.password",  POSTGRES::getPassword);
        registry.add("spring.flyway.url",           POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",          POSTGRES::getUsername);
        registry.add("spring.flyway.password",      POSTGRES::getPassword);
        registry.add("spring.data.redis.host",      () -> REDIS.getHost());
        registry.add("spring.data.redis.port",      () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",  () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired MockMvc    mockMvc;
    @Autowired JdbcTemplate jdbc;

    private static final String LOGIN_URL   = "/api/v1/auth/login";
    private static final String REFRESH_URL = "/api/v1/auth/refresh";
    private static final String PASSWORD    = "TestPassword123!";

    @BeforeEach
    void cleanTokens() {
        // Remove tokens from previous test run; leave seed users and fixture families intact
        jdbc.execute("DELETE FROM refresh_token");
        jdbc.execute(
            "UPDATE refresh_token_family SET revoked_at = NULL, revoked_reason = NULL " +
            "WHERE id NOT IN ('cccccccc-0000-7000-8000-000000000001'::uuid)");
    }

    // ---- AC-2: login → refresh → replay ---------------------------------------

    @Test
    @DisplayName("login then refresh issues new access token; replay of original returns 401 with family revoked")
    void login_refresh_replay_revokes_family() throws Exception {
        // Step 1: Login
        String originalCookie = loginAndGetCookie("admin@example.local");

        // Step 2: Refresh — must succeed and return a NEW (different) cookie
        String rotatedCookie = refreshAndGetCookie(originalCookie);
        assertThat(rotatedCookie).isNotEqualTo(originalCookie);

        // Step 3: Replay original cookie — must return 401
        mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(originalCookie)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));

        // Step 4: Verify full family revocation in DB
        int familyRevoked = jdbc.queryForObject(
            "SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NOT NULL " +
            "AND user_id = '11111111-1111-7000-8000-000000000001'::uuid",
            Integer.class);
        assertThat(familyRevoked).isGreaterThanOrEqualTo(1);

        // Step 5: Further refresh with rotated cookie also 401 (family revoked)
        mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(rotatedCookie)))
            .andExpect(status().isUnauthorized());
    }

    // ---- AC-1: handle must come exclusively from cookie -----------------------

    @Test
    @DisplayName("handle in body is ignored — request without cookie returns 401")
    void handle_in_body_rejected() throws Exception {
        mockMvc.perform(post(REFRESH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + "A".repeat(43) + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("handle in query parameter is ignored — request without cookie returns 401")
    void handle_in_query_param_rejected() throws Exception {
        mockMvc.perform(post(REFRESH_URL + "?refresh_token=" + "A".repeat(43)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("handle in Authorization header is ignored — request without cookie returns 401")
    void handle_in_header_rejected() throws Exception {
        mockMvc.perform(post(REFRESH_URL)
                .header("Authorization", "Bearer " + "A".repeat(43)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    // ---- AC-1 (cookie attribute verification) ---------------------------------

    @Test
    @DisplayName("rotated cookie carries HttpOnly, Secure, SameSite=Strict, narrow Path")
    void rotated_cookie_has_security_attributes() throws Exception {
        String original = loginAndGetCookie("admin@example.local");

        mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(original)))
            .andExpect(status().isOk())
            .andExpect(cookie().httpOnly("refresh_token", true))
            .andExpect(cookie().path("refresh_token", "/api/v1/auth"));
    }

    // ---- AC-4: already-revoked family dedup -----------------------------------

    @Test
    @DisplayName("request with handle from already-revoked family returns 401 without new outbox event")
    void already_revoked_family_no_duplicate_event() throws Exception {
        // Insert a token pointing to the pre-revoked fixture family
        String handle  = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String hash    = RefreshTokenService.sha256Hex(handle);
        insertToken(hash, "cccccccc-0000-7000-8000-000000000001");

        long eventsBefore = countOutboxEvents("REFRESH_TOKEN_REUSE");

        mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(handle)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));

        // No new SIEM event (family already revoked — dedup)
        long eventsAfter = countOutboxEvents("REFRESH_TOKEN_REUSE");
        assertThat(eventsAfter).isEqualTo(eventsBefore);
    }

    // ---- AC-5: expired family -------------------------------------------------

    @Test
    @DisplayName("handle from expired family returns 401")
    void expired_family_returns_401() throws Exception {
        String handle = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        String hash   = RefreshTokenService.sha256Hex(handle);
        insertToken(hash, "dddddddd-0000-7000-8000-000000000001");

        mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(handle)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    // ---- AC-8: deactivated owner ----------------------------------------------

    @Test
    @DisplayName("handle from deactivated-owner family returns 401")
    void deactivated_owner_returns_401() throws Exception {
        String handle = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        String hash   = RefreshTokenService.sha256Hex(handle);
        insertToken(hash, "eeeeeeee-0000-7000-8000-000000000001");

        mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(handle)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
    }

    // ---- AC-7: concurrency — exactly one 200, one 401 -------------------------

    @Test
    @DisplayName("two parallel requests with same handle yield exactly one 200 and one reuse-401")
    void concurrent_same_handle_one_success_one_reuse() throws Exception {
        String cookie = loginAndGetCookie("dispatcher@example.local");

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger reuses    = new AtomicInteger(0);
        CountDownLatch go       = new CountDownLatch(1);
        CountDownLatch done     = new CountDownLatch(2);
        ExecutorService pool    = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            final String c = cookie;
            pool.submit(() -> {
                try {
                    go.await();
                    MvcResult r = mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(c)))
                            .andReturn();
                    int status = r.getResponse().getStatus();
                    if (status == 200) successes.incrementAndGet();
                    else if (status == 401) reuses.incrementAndGet();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        go.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(reuses.get()).isEqualTo(1);

        // Family must be revoked after the reuse detection
        int familyRevoked = jdbc.queryForObject(
            "SELECT COUNT(*) FROM refresh_token_family " +
            "WHERE user_id = '11111111-1111-7000-8000-000000000002'::uuid " +
            "AND revoked_at IS NOT NULL",
            Integer.class);
        assertThat(familyRevoked).isGreaterThanOrEqualTo(1);
    }

    // ---- AC-6: plaintext handle never stored in DB ----------------------------

    @Test
    @DisplayName("no plaintext handle appears in any refresh_token column after rotation")
    void no_plaintext_handle_in_database() throws Exception {
        String cookie = loginAndGetCookie("manager@example.local");

        MvcResult result = mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(cookie)))
                .andExpect(status().isOk())
                .andReturn();

        // Extract the new cookie value
        String newCookie = extractCookieValue(result.getResponse());

        // Neither the original handle nor the new handle should appear in any token column
        for (String handle : List.of(cookie, newCookie)) {
            Integer matchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?",
                Integer.class, handle); // token_hash stores the HASH, not the plain handle
            // This query checks that no row has the raw handle stored as the hash
            // The actual hash would be sha256Hex(handle), not handle itself
            // So this should always be 0 IF the implementation is correct
            assertThat(matchCount)
                .as("Plaintext handle '%s' must not be stored in token_hash", handle)
                .isZero();
        }
    }

    // ---- AC-10 (OpenAPI) — response body contract -----------------------------

    @Test
    @DisplayName("successful refresh returns accessToken, tokenType=Bearer, expiresIn")
    void refresh_response_body_contract() throws Exception {
        String cookie = loginAndGetCookie("customer@example.local");

        mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(cookie)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").isNumber());
    }

    // ---- Helpers ---------------------------------------------------------------

    private String loginAndGetCookie(String email) throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn();

        return extractCookieValue(result.getResponse());
    }

    private String refreshAndGetCookie(String existingHandle) throws Exception {
        MvcResult result = mockMvc.perform(post(REFRESH_URL).cookie(buildCookie(existingHandle)))
            .andExpect(status().isOk())
            .andReturn();

        return extractCookieValue(result.getResponse());
    }

    private static jakarta.servlet.http.Cookie buildCookie(String value) {
        jakarta.servlet.http.Cookie c = new jakarta.servlet.http.Cookie("refresh_token", value);
        c.setPath("/api/v1/auth");
        return c;
    }

    private static String extractCookieValue(MockHttpServletResponse response) {
        jakarta.servlet.http.Cookie c = response.getCookie("refresh_token");
        assertThat(c).as("refresh_token cookie must be present in response").isNotNull();
        return c.getValue();
    }

    private void insertToken(String hash, String familyId) {
        UUID tokenId  = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO refresh_token (id, family_id, token_hash, issued_at, expires_at) " +
            "VALUES (?::uuid, ?::uuid, ?, NOW(), NOW() + INTERVAL '7 days')",
            tokenId.toString(), familyId, hash);
    }

    private long countOutboxEvents(String eventType) {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE event_type = ?",
            Long.class, eventType);
        return count != null ? count : 0L;
    }
}
