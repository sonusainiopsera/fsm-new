package com.fieldservice.api;

import com.fieldservice.api.support.ApiAssertions;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 contract conformance tests for the auth endpoint group (WO-204, AC-1, AC-3).
 *
 * <p>Supplements {@code LoginIntegrationTest} with envelope-level contract assertions:
 * <ul>
 *   <li>Login success response shape: tokenType, expiresIn, accessToken, user</li>
 *   <li>Invalid-credentials error envelope: code, message, fieldErrors=[], traceId, no leaked internals</li>
 *   <li>Lockout after repeated failures returns INVALID_CREDENTIALS — not ACCOUNT_LOCKED (prevents probing)</li>
 *   <li>Unknown-property rejection (strict-schema): POST login with extra field → 400</li>
 * </ul>
 *
 * <p>Refresh-rotation, logout-revocation, and stream-ticket tests are covered by
 * {@code LoginIntegrationTest} and {@code RefreshTokenRotationTest}. This class focuses on
 * the contract envelope assertions that are the subject of WO-204.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Auth endpoint group — P0 contract conformance")
class AuthContractTest extends AbstractIntegrationTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String FIXTURE_PASSWORD = "Fixture@1234!Pass";

    @Autowired MockMvc mockMvc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setFixturePasswords() {
        String hash = passwordEncoder.encode(FIXTURE_PASSWORD);
        jdbcTemplate.update(
                "UPDATE app_user SET password_hash = ? WHERE email LIKE 'id.%@example.com' AND password_hash IS NOT NULL",
                hash);
    }

    // ── Successful login response shape ─────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login success → tokenType, expiresIn, accessToken, user")
    void login_success_envelopeShape() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("id.admin@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user").exists())
                .andExpect(jsonPath("$.user.displayName").isString())
                .andExpect(jsonPath("$.user.roles").isArray());
    }

    @Test
    @DisplayName("POST /auth/login success → refreshToken cookie is HttpOnly+Secure+SameSite=Strict")
    void login_success_refreshTokenCookieAttributes() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("id.admin@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).as("Set-Cookie header must be present").isNotNull();
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=Strict");
    }

    // ── Error envelope conformance ───────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login with wrong password → 401 INVALID_CREDENTIALS error envelope")
    void login_invalidCredentials_errorShape() throws Exception {
        ApiAssertions.assertErrorShape(
                mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("id.admin@example.com", "wrong-password")))
                        .andExpect(status().isUnauthorized()),
                ErrorEnvelope.Code.INVALID_CREDENTIALS,
                0);
    }

    @Test
    @DisplayName("POST /auth/login with wrong password → no internal details in error body")
    void login_invalidCredentials_noInternalLeak() throws Exception {
        ApiAssertions.assertNoInternalLeak(
                mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("id.admin@example.com", "wrong-password")))
                        .andExpect(status().isUnauthorized()));
    }

    @Test
    @DisplayName("POST /auth/login with nonexistent email → same 401 INVALID_CREDENTIALS (no probing)")
    void login_nonExistentUser_sameCodeAsWrongPassword() throws Exception {
        ApiAssertions.assertErrorShape(
                mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nonexistent@example.com", "any-password")))
                        .andExpect(status().isUnauthorized()),
                ErrorEnvelope.Code.INVALID_CREDENTIALS,
                0);
    }

    @Test
    @DisplayName("POST /auth/login after lockout → still INVALID_CREDENTIALS, not ACCOUNT_LOCKED")
    void login_afterLockout_codeDoesNotRevealLockoutState() throws Exception {
        // Drive enough failures to trigger lockout
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(post(LOGIN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("id.dispatcher@example.com", "wrong-" + i)));
        }
        // After lockout the code must remain INVALID_CREDENTIALS — not ACCOUNT_LOCKED or LOCKED_OUT
        String code = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("id.dispatcher@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(code)
                .as("Locked-out account must not return ACCOUNT_LOCKED (prevents enumeration)")
                .contains(ErrorEnvelope.Code.INVALID_CREDENTIALS)
                .doesNotContain("ACCOUNT_LOCKED")
                .doesNotContain("LOCKED_OUT");
    }

    // ── Strict schema rejection ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login with unknown JSON property → 400 VALIDATION_FAILED")
    void login_unknownProperty_isRejected() throws Exception {
        String bodyWithExtra = """
                {"email":"id.admin@example.com","password":"%s","unknownField":"injected"}
                """.formatted(FIXTURE_PASSWORD);

        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithExtra))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.VALIDATION_FAILED));
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private static String loginBody(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }
}
