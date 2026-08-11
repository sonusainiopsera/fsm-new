package com.fieldservice.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.api.dto.LoginRequest;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.security.SecurityFilterChainTestConfig;
import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.security.TestTokenMinter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System integration tests for POST /api/v1/auth/logout.
 *
 * <h3>Setup</h3>
 * Uses real Testcontainers PostgreSQL and Redis so the full sequence is exercised against
 * committed rows. Imports {@link SecurityFilterChainTestConfig} so JWTs signed by
 * {@link TestTokenMinter} pass the denylist-aware validator — the standard test stub
 * decoder is replaced because the denylist enforcement path must be exercised.
 *
 * <h3>Token strategy</h3>
 * Access tokens are minted offline via {@link TestTokenMinter#primary()} using the test RSA
 * key pair. The login endpoint is still called to create the refresh-token family in the
 * database; only the refresh handle (cookie) from the login response is used — the access
 * token returned by login is discarded because it is signed with the ephemeral production key,
 * not the test key pair.
 *
 * <p>Satisfies AC-11: "login → authenticated 200 → logout 204 → authenticated 401 → refresh 401."
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityFilterChainTestConfig.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
@DisplayName("Logout integration tests")
class LogoutIntegrationTest {

    private static final String LOGIN_URL   = "/api/v1/auth/login";
    private static final String LOGOUT_URL  = "/api/v1/auth/logout";
    private static final String REFRESH_URL = "/api/v1/auth/refresh";
    // Any authenticated endpoint reachable by ADMIN
    private static final String PROTECTED_URL = "/api/v1/work-orders";

    private static final String FIXTURE_PASSWORD = "Fixture@1234!Pass";

    private static final Pattern COOKIE_VALUE_PATTERN =
            Pattern.compile("refreshToken=([^;]+)");

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fieldservice_test")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwks-uri",
                () -> "http://localhost:0/.well-known/jwks.json");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Clear the test-profile Redis autoconfiguration exclusion so the denylist works.
        registry.add("spring.autoconfigure.exclude", () -> "");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setFixturePasswords() {
        String hash = passwordEncoder.encode(FIXTURE_PASSWORD);
        jdbcTemplate.update(
                "UPDATE app_user SET password_hash = ? " +
                "WHERE email LIKE 'id.%@example.com' AND password_hash IS NOT NULL",
                hash);
    }

    // -----------------------------------------------------------------------
    // AC-11: Full lifecycle — login → 200 → logout 204 → 401 → refresh 401
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Full lifecycle: login → authenticated 200 → logout 204 → 401 → refresh 401")
    void fullLifecycle_loginThenLogout_subsequentRequestsReturn401() throws Exception {
        // Mint a test token with a known jti for deterministic denylist assertion.
        String jti = UUID.randomUUID().toString();
        String accessToken = TestTokenMinter.primary()
                .withJti(TestJwtFactory.ADMIN_USER_ID, List.of("ADMIN"), jti);

        // Login only to create the refresh family in the DB; discard its access token.
        String refreshHandle = loginForRefreshHandle("id.admin@example.com");

        // 1. Authenticated request with minted token → 200
        mockMvc.perform(get(PROTECTED_URL)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 2. Logout with minted token + refresh cookie → 204 with clearing Set-Cookie
        MvcResult logoutResult = mockMvc.perform(post(LOGOUT_URL)
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie("refreshToken", refreshHandle)))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = logoutResult.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).as("Logout must set a cookie-clearing header").isNotNull();
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("Path=/api/v1/auth");

        // 3. Same token is denylisted → 401
        mockMvc.perform(get(PROTECTED_URL)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        // 4. Refresh with the revoked family's cookie → 401
        mockMvc.perform(post(REFRESH_URL)
                        .cookie(new Cookie("refreshToken", refreshHandle)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.REAUTHENTICATION_REQUIRED));
    }

    // -----------------------------------------------------------------------
    // AC-2: Family revoked in DB with correct reason
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("After logout, refresh_token_family is revoked with USER_LOGOUT reason")
    void afterLogout_familyIsMarkedRevoked() throws Exception {
        String refreshHandle = loginForRefreshHandle("id.dispatcher@example.com");

        mockMvc.perform(post(LOGOUT_URL)
                        .cookie(new Cookie("refreshToken", refreshHandle)))
                .andExpect(status().isNoContent());

        int revokedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token_family " +
                "WHERE revoked_at IS NOT NULL AND revoked_reason = 'USER_LOGOUT'",
                Integer.class);
        assertThat(revokedCount).isGreaterThanOrEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC-8: Audit outbox event committed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("After logout, a UserLoggedOut outbox event is committed")
    void afterLogout_outboxEventIsCommitted() throws Exception {
        String refreshHandle = loginForRefreshHandle("id.manager@example.com");

        int beforeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'UserLoggedOut'",
                Integer.class);

        mockMvc.perform(post(LOGOUT_URL)
                        .cookie(new Cookie("refreshToken", refreshHandle)))
                .andExpect(status().isNoContent());

        int afterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'UserLoggedOut'",
                Integer.class);
        assertThat(afterCount).isEqualTo(beforeCount + 1);
    }

    // -----------------------------------------------------------------------
    // AC-4: Idempotency
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Double logout → both return 204 (idempotent)")
    void doubleLogout_returns204Both() throws Exception {
        String refreshHandle = loginForRefreshHandle("id.admin@example.com");

        mockMvc.perform(post(LOGOUT_URL)
                        .cookie(new Cookie("refreshToken", refreshHandle)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(LOGOUT_URL)
                        .cookie(new Cookie("refreshToken", refreshHandle)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Logout with no cookie → 204 with clearing Set-Cookie")
    void noCookie_returns204WithClearCookie() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGOUT_URL))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("refreshToken=");
        assertThat(setCookie).contains("Max-Age=0");
    }

    // -----------------------------------------------------------------------
    // AC-5: Expired access token — family still revoked
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Expired access token in header → family still revoked, 204")
    void expiredAccessToken_familyRevoked() throws Exception {
        String refreshHandle = loginForRefreshHandle("id.admin@example.com");
        // Expired token signed with the test key (we can parse it without validation)
        String expiredToken = TestTokenMinter.primary()
                .expired(TestJwtFactory.ADMIN_USER_ID, List.of("ADMIN"));

        mockMvc.perform(post(LOGOUT_URL)
                        .header("Authorization", "Bearer " + expiredToken)
                        .cookie(new Cookie("refreshToken", refreshHandle)))
                .andExpect(status().isNoContent());

        int revokedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token_family " +
                "WHERE revoked_at IS NOT NULL AND revoked_reason = 'USER_LOGOUT'",
                Integer.class);
        assertThat(revokedCount).isGreaterThanOrEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC-6: Device scope — other families unaffected
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Logout only revokes the presented family, not other device sessions")
    void logout_scopedToSingleFamily() throws Exception {
        String refreshHandle1 = loginForRefreshHandle("id.admin@example.com");
        String refreshHandle2 = loginForRefreshHandle("id.admin@example.com");

        int beforeActive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NULL",
                Integer.class);

        // Logout only device 1
        mockMvc.perform(post(LOGOUT_URL)
                        .cookie(new Cookie("refreshToken", refreshHandle1)))
                .andExpect(status().isNoContent());

        int afterActive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NULL",
                Integer.class);
        assertThat(afterActive).isEqualTo(beforeActive - 1);

        // Device 2 refresh still works
        mockMvc.perform(post(REFRESH_URL)
                        .cookie(new Cookie("refreshToken", refreshHandle2)))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Calls login and returns the refresh handle from the Set-Cookie header. */
    private String loginForRefreshHandle(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(email, FIXTURE_PASSWORD));
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String setCookieHeader = result.getResponse().getHeader("Set-Cookie");
        Matcher m = COOKIE_VALUE_PATTERN.matcher(setCookieHeader != null ? setCookieHeader : "");
        return m.find() ? m.group(1) : null;
    }
}
