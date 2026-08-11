package com.fieldservice.identity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for POST /api/v1/auth/login.
 *
 * <p>Uses Testcontainers PostgreSQL + Redis so no mocks are substituted for
 * infrastructure. The full Spring context starts (excluding swagger-ui).
 *
 * <p>BCrypt cost 12 is deliberately preserved — BCrypt at strength 12 takes ~250 ms
 * per verify, which is expected behaviour per the security policy. Tests here are
 * annotated {@code @Tag("integration")} and should be excluded from the fast unit-test
 * Maven Surefire run; include them in the integration Failsafe phase.
 */
@Tag("integration")
@Sql(scripts = "/fixtures/identity-seed.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Clear the Redis autoconfigure exclusion set in application-test.yml
    // so that RedisLoginAttemptTracker is active for this integration test.
    properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
    }
)
@AutoConfigureMockMvc
@Testcontainers
class LoginIntegrationTest {

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
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
    }

    @Autowired
    MockMvc mockMvc;

    private static final String LOGIN_URL  = "/api/v1/auth/login";
    // Pre-computed BCrypt cost-12 hash of 'TestPassword123!'
    private static final String PASSWORD   = "TestPassword123!";

    @Test
    void successful_login_returns_200_with_access_token_and_cookie() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin@example.local", PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").isNumber())
            .andExpect(jsonPath("$.user.id").isNotEmpty())
            .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"))
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(cookie().httpOnly("refresh_token", true))
            .andExpect(cookie().path("refresh_token", "/api/v1/auth"))
            .andExpect(cookie().maxAge("refresh_token", (int) java.time.Duration.ofDays(7).getSeconds()));
    }

    @Test
    void access_token_not_in_cookie() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin@example.local", PASSWORD)))
            .andExpect(status().isOk())
            .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        // The Set-Cookie must be for refresh_token, not contain an access token
        assertThat(setCookie).doesNotContain("accessToken");
        assertThat(setCookie).doesNotContain("access_token");
    }

    @Test
    void five_failures_produce_identical_401_responses() throws Exception {
        String body1 = null;
        String body5 = null;
        for (int i = 1; i <= 5; i++) {
            MvcResult r = mockMvc.perform(post(LOGIN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("dispatcher@example.local", "wrong-password-x")))
                .andExpect(status().isUnauthorized())
                .andReturn();
            String body = r.getResponse().getContentAsString();
            if (i == 1) body1 = body;
            if (i == 5) body5 = body;
        }
        assertThat(body1).isEqualTo(body5);
    }

    @Test
    void sixth_attempt_still_returns_401_after_lockout() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(LOGIN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("technician@example.local", "wrong-password-x")))
                .andExpect(status().isUnauthorized());
        }
        // Sixth attempt with correct password — still 401 due to lockout
        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("technician@example.local", PASSWORD)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void unknown_email_and_wrong_password_return_identical_bodies() throws Exception {
        MvcResult unknownResult = mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("nobody@example.local", PASSWORD)))
            .andExpect(status().isUnauthorized())
            .andReturn();

        MvcResult wrongPassResult = mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("manager@example.local", "WrongPass1234!")))
            .andExpect(status().isUnauthorized())
            .andReturn();

        assertThat(unknownResult.getResponse().getContentAsString())
                .isEqualTo(wrongPassResult.getResponse().getContentAsString());
    }

    @Test
    void inactive_user_returns_401() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("inactive@example.local", PASSWORD)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void grantless_user_returns_401() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("grantless@example.local", PASSWORD)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void invalid_request_body_returns_422() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknown_property_in_request_body_returns_400() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"password\":\"ValidPass123!\",\"extra\":\"field\"}"))
            .andExpect(status().isBadRequest());
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
