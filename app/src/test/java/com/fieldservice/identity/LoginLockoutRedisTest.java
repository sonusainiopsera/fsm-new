package com.fieldservice.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.api.dto.LoginRequest;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.security.TestSecurityConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for login lockout with real Redis (Testcontainers).
 *
 * <p>Uses the {@code login-test} profile so Redis autoconfiguration is not excluded,
 * allowing the real {@link com.fieldservice.identity.application.RedisLoginAttemptTracker} to be used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("login-test")
@Import(TestSecurityConfig.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginLockoutRedisTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String FIXTURE_PASSWORD = "Fixture@1234!Pass";
    private static final String TEST_EMAIL = "id.customer@example.com";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fieldservice_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.security.oauth2.resourceserver.jwt.jwks-uri",
                () -> "http://localhost:0/.well-known/jwks.json");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeAll
    void setFixturePasswords() {
        String hash = passwordEncoder.encode(FIXTURE_PASSWORD);
        jdbcTemplate.update(
                "UPDATE app_user SET password_hash = ? WHERE email LIKE 'id.%@example.com' AND password_hash IS NOT NULL",
                hash);
    }

    @Test
    void redisCounterHasTtlAfterFirstFailure() throws Exception {
        String uniqueEmail = "lockout.ttl@example.com";

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(uniqueEmail, "WrongPass999!!")))
                .andExpect(status().isUnauthorized());

        String key = "login:fail:" + sha256Hex(uniqueEmail);
        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isNotNull();
        // TTL should be approximately 900 seconds (allow a few seconds for test execution)
        assertThat(ttl).isBetween(890L, 900L);
    }

    @Test
    void successfulLogin_clearsRedisCounter() throws Exception {
        // Record two failures
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(LOGIN_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(TEST_EMAIL, "WrongPass999!!")))
                    .andExpect(status().isUnauthorized());
        }

        String key = "login:fail:" + sha256Hex(TEST_EMAIL);
        assertThat(redisTemplate.opsForValue().get(key)).isNotNull();

        // Successful login clears the key
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(TEST_EMAIL, FIXTURE_PASSWORD)))
                .andExpect(status().isOk());

        assertThat(redisTemplate.opsForValue().get(key)).isNull();
    }

    @Test
    void fiveFailures_lockout_sixthIsAlso401() throws Exception {
        String email = "lockout.six@example.com";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(LOGIN_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(email, "WrongPass999!!")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.INVALID_CREDENTIALS));
    }

    private String json(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(email, password));
    }

    private static String sha256Hex(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
