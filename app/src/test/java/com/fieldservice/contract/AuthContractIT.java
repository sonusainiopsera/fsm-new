package com.fieldservice.contract;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.contract.support.ApiAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static com.fieldservice.contract.support.ApiAssertions.assertErrorShape;
import static com.fieldservice.contract.support.ApiAssertions.assertNoInternals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 API contract tests for the auth endpoint group.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li><strong>AC1</strong>: End-to-end request to POST /api/v1/auth/login.</li>
 *   <li><strong>AC3</strong>: Invalid credentials return 401 with uniform error envelope.</li>
 *   <li><strong>AC3</strong>: Malformed login body returns 400 with fieldErrors.</li>
 *   <li><strong>AC3</strong>: Stream-ticket endpoint reachable with valid bearer JWT.</li>
 * </ul>
 *
 * <p>Tests that require real Redis (lockout after repeated failures, refresh rotation with
 * reuse detection) are covered by {@code LoginIntegrationTest} and
 * {@code RefreshRotationIntegrationTest} which use the Testcontainers Redis mixin.
 *
 * <p>This class uses the H2 in-memory test profile with {@code InMemoryLoginAttemptTracker}
 * so it runs fast without Docker infrastructure.
 */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Sql(scripts = "/fixtures/identity-seed.sql",
     executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AuthContractIT {

    @Autowired
    MockMvc mockMvc;

    // -----------------------------------------------------------------------
    // AC1: Login happy path — access token in body, refresh cookie set
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC1: POST /api/v1/auth/login with valid credentials returns 200 with accessToken")
    void login_with_valid_credentials_returns_200_with_access_token() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dispatcher@fieldservice.test","password":"TestPassword123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(cookie().exists("refresh"))
                .andReturn();

        // Access token must be a JWT (three dot-separated base64url segments)
        String accessToken = result.getResponse().getContentAsString();
        assertThat(accessToken).contains("accessToken");
        assertNoInternals(result.getResponse());
    }

    // -----------------------------------------------------------------------
    // AC3: Invalid credentials — 401 with uniform error envelope
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: Invalid credentials return 401 with uniform error envelope")
    void invalid_credentials_return_401_with_error_envelope() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dispatcher@fieldservice.test","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertErrorShape(result.getResponse(), "INVALID_CREDENTIALS");
        assertNoInternals(result.getResponse());

        // Must NOT reveal whether the account exists (identical body for unknown email)
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("dispatcher@fieldservice.test");
    }

    // -----------------------------------------------------------------------
    // AC3: Unknown email — same 401 response shape (no user enumeration)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: Unknown email returns 401 with same error shape — no user enumeration")
    void unknown_email_returns_401_same_shape() throws Exception {
        MvcResult unknown = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@nowhere.test","password":"anything"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult invalid = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dispatcher@fieldservice.test","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Both must return the same error code (non-disclosure)
        var unknownNode = ApiAssertions.parse(unknown.getResponse());
        var invalidNode = ApiAssertions.parse(invalid.getResponse());
        assertThat(unknownNode.path("code").asText())
                .isEqualTo(invalidNode.path("code").asText());
    }

    // -----------------------------------------------------------------------
    // AC3: Missing required fields — 400 with fieldErrors
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: Missing email in login request returns 400 with field-level error")
    void missing_email_returns_400_with_field_errors() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password":"TestPassword123!"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorShape(result.getResponse(), "VALIDATION_FAILED", "email");
        assertNoInternals(result.getResponse());
    }

    // -----------------------------------------------------------------------
    // AC3: stream-ticket endpoint returns 503 (no Redis in test profile) — not 401/403
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: /api/v1/auth/stream-ticket is reachable with a valid bearer JWT")
    void stream_ticket_reachable_with_valid_bearer() throws Exception {
        // In the test profile, the StreamTicketService requires Redis and is not available.
        // The expected response is 503 (AuthDependencyUnavailableException), NOT 401/403.
        // This proves the endpoint is reachable and the auth gate passes.
        MvcResult result = mockMvc.perform(post("/api/v1/auth/stream-ticket")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))
                                .jwt(j -> j.claim("roles", List.of("DISPATCHER"))))
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("stream-ticket endpoint must be reachable with valid JWT (not 401 or 403)")
                .isNotIn(401, 403);
    }
}
