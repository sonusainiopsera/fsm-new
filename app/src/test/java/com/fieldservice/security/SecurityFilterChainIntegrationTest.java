package com.fieldservice.security;

import com.fieldservice.identity.token.JtiDenylist;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the OAuth2 Resource Server security filter chain.
 *
 * <p>Uses a Testcontainers PostgreSQL and Redis instance, the production security
 * configuration, and JWTs minted with the test RSA key pair from {@link TestRsaKeyPair}.
 * Covers all rejection scenarios from the WO-112 acceptance criteria.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("login-test")
@Import(SecurityFilterChainTestConfig.class)
@Testcontainers
class SecurityFilterChainIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fieldservice_sec_test")
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

    private static final UUID TEST_USER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final TestTokenMinter MINTER = TestTokenMinter.primary();
    private static final String PROTECTED_URL = "/api/v1/work-orders";

    @Autowired MockMvc mockMvc;
    @Autowired JtiDenylist jtiDenylist;

    // -------------------------------------------------------------------------
    // AC1 / AC9 — public paths, deny-by-default, security headers
    // -------------------------------------------------------------------------

    @Test
    void healthEndpoint_isPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void authLoginPath_isPubliclyAccessible_badInputReturns400NotUnauthorized() throws Exception {
        // POST /api/v1/auth/login is permitAll — invalid payload returns 400 not 401
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@y.com\",\"password\":\"short\"}")
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void protectedPath_withoutToken_returns401WithErrorContract() throws Exception {
        mockMvc.perform(get(PROTECTED_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    @Test
    void anyProtectedResponse_hasRequiredSecurityHeaders() throws Exception {
        mockMvc.perform(get(PROTECTED_URL))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("max-age=31536000")))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("includeSubDomains")))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("preload")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    // -------------------------------------------------------------------------
    // AC2 — JWT rejection scenarios
    // -------------------------------------------------------------------------

    @Test
    void missingToken_returns401() throws Exception {
        mockMvc.perform(get(PROTECTED_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void malformedBearerHeader_returns401() throws Exception {
        mockMvc.perform(get(PROTECTED_URL)
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void expiredToken_returns401() throws Exception {
        String token = MINTER.expired(TEST_USER, List.of("DISPATCHER"));
        mockMvc.perform(get(PROTECTED_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void wrongIssuerToken_returns401() throws Exception {
        String token = MINTER.wrongIssuer(TEST_USER, List.of("DISPATCHER"));
        mockMvc.perform(get(PROTECTED_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void wrongAudienceToken_returns401() throws Exception {
        String token = MINTER.wrongAudience(TEST_USER, List.of("DISPATCHER"));
        mockMvc.perform(get(PROTECTED_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void misSignedToken_returns401() throws Exception {
        String token = TestTokenMinter.misSignedToken(TEST_USER, List.of("DISPATCHER"));
        mockMvc.perform(get(PROTECTED_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void allRejections_returnGenericMessageWithoutTokenDetails() throws Exception {
        // Verify no specific failure reason is disclosed
        String expired = MINTER.expired(TEST_USER, List.of("DISPATCHER"));
        String wrongIss = MINTER.wrongIssuer(TEST_USER, List.of("DISPATCHER"));

        for (String token : new String[]{expired, wrongIss}) {
            mockMvc.perform(get(PROTECTED_URL)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.message").value("Authentication required."));
        }
    }

    // -------------------------------------------------------------------------
    // AC5 — JTI denylist
    // -------------------------------------------------------------------------

    @Test
    void denylisted_jti_returns401EvenWithValidToken() throws Exception {
        String jti = UUID.randomUUID().toString();
        String token = MINTER.withJti(TEST_USER, List.of("DISPATCHER"), jti);

        // Revoke the token's jti
        jtiDenylist.revoke(jti, Duration.ofSeconds(900));

        mockMvc.perform(get(PROTECTED_URL)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // -------------------------------------------------------------------------
    // AC10 — XML body is rejected with 415
    // -------------------------------------------------------------------------

    @Test
    void xmlRequestBody_returns415() throws Exception {
        String token = MINTER.valid(TEST_USER, List.of("DISPATCHER"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_XML)
                        .header("Authorization", "Bearer " + token)
                        .content("<login><email>a@b.com</email></login>"))
                .andExpect(status().isUnsupportedMediaType());
    }

    // -------------------------------------------------------------------------
    // AC11 — No default credentials or permitAll on domain endpoints
    // -------------------------------------------------------------------------

    @Test
    void noDefaultCredentials_unauthenticatedRequestsAlwaysReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders"))
                .andExpect(status().isUnauthorized());
    }
}
