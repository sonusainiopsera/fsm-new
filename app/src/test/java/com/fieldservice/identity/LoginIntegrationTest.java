package com.fieldservice.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.api.dto.LoginRequest;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.security.AbstractIntegrationTest;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.text.ParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /api/v1/auth/login against Testcontainers PostgreSQL.
 *
 * <p>Uses the in-memory LoginAttemptTracker (Redis is excluded in the test profile) so
 * lockout logic still functions but without real Redis TTL assertions (see LoginLockoutRedisTest).
 *
 * <p>Fixture users' password hashes are set programmatically in @BeforeAll because the
 * V103 fixture has placeholder BCrypt strings that are not valid encoded values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginIntegrationTest extends AbstractIntegrationTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String FIXTURE_PASSWORD = "Fixture@1234!Pass";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeAll
    void setFixturePasswords() {
        String hash = passwordEncoder.encode(FIXTURE_PASSWORD);
        jdbcTemplate.update(
                "UPDATE app_user SET password_hash = ? WHERE email LIKE 'id.%@example.com' AND password_hash IS NOT NULL",
                hash);
    }

    @Test
    void successfulLogin_returns200WithTokenAndCookie() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("id.admin@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.displayName").value("Identity Admin"))
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"))
                .andReturn();

        // Cookie must be HttpOnly, Secure, SameSite=Strict
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("Path=/api/v1/auth");
        assertThat(setCookie).contains("Max-Age=");
        assertThat(setCookie).startsWith("refreshToken=");
    }

    @Test
    void successfulLogin_jwtClaimsAreCorrect() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("id.dispatcher@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asText();
        var claims = parseClaimsUnchecked(accessToken);

        assertThat(claims.getSubject()).isNotBlank();
        assertThat(claims.getStringListClaim("roles")).containsExactly("DISPATCHER");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getIssueTime()).isNotNull();
        assertThat(claims.getExpirationTime()).isNotNull();
        assertThat(claims.getIssuer()).isNotBlank();
        assertThat(claims.getAudience()).isNotEmpty();
        // expiresIn = 15 minutes
        long ttl = (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000;
        assertThat(ttl).isEqualTo(900L);
    }

    @Test
    void unknownEmail_returns401WithGenericMessage() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("nobody@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.message").value("Invalid credentials."))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void wrongPassword_returns401WithGenericMessage() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("id.admin@example.com", "WrongPass999!!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.INVALID_CREDENTIALS))
                .andExpect(jsonPath("$.message").value("Invalid credentials."));
    }

    @Test
    void inactiveUser_returns401IdenticalToWrongPassword() throws Exception {
        MvcResult inactive = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("id.inactive@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult wrongPw = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("id.admin@example.com", "WrongPass999!!")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Same code and message — indistinguishable
        var inactiveBody = objectMapper.readTree(inactive.getResponse().getContentAsString());
        var wrongPwBody = objectMapper.readTree(wrongPw.getResponse().getContentAsString());
        assertThat(inactiveBody.get("code").asText()).isEqualTo(wrongPwBody.get("code").asText());
        assertThat(inactiveBody.get("message").asText()).isEqualTo(wrongPwBody.get("message").asText());
    }

    @Test
    void grantlessUser_returns401() throws Exception {
        // Grantless user has null password_hash — still returns 401
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("id.grantless@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.INVALID_CREDENTIALS));
    }

    @Test
    void fiveFailures_lockout_sixthAttemptAlsoReturns401() throws Exception {
        String email = "id.technician@example.com";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(LOGIN_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(email, "WrongPass999!!")))
                    .andExpect(status().isUnauthorized());
        }
        // 6th attempt with correct password — still 401 (locked)
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, FIXTURE_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.INVALID_CREDENTIALS));
    }

    @Test
    void validationError_missingEmail_returns400() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Test@1234!Pass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.VALIDATION_FAILED))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void validationError_passwordTooShort_returns400() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("user@example.com", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.VALIDATION_FAILED));
    }

    @Test
    void unknownJsonProperty_returns400() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"u@x.com\",\"password\":\"Test@1234!Pass\",\"extra\":\"val\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshTokenHash_storedNotPlaintext() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("id.manager@example.com", FIXTURE_PASSWORD)))
                .andExpect(status().isOk());

        // Verify: no refresh token plaintext is stored; only 64-char SHA-256 hashes exist
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE length(token_hash) = 64", Integer.class);
        assertThat(count).isGreaterThan(0);

        // token_hash is the SHA-256 hex, not a JWT or base64url value
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM refresh_token ORDER BY issued_at DESC LIMIT 1", String.class);
        assertThat(storedHash).matches("[0-9a-f]{64}");
        assertThat(storedHash).doesNotContain(".");  // no JWT dot notation
    }

    @Test
    void allFailureModes_returnIdenticalCodeAndMessage() throws Exception {
        String[] scenarios = {
                json("nobody@example.com", FIXTURE_PASSWORD),     // unknown email
                json("id.admin@example.com", "WrongPass999!!"),   // wrong password
                json("id.inactive@example.com", FIXTURE_PASSWORD) // inactive
        };

        String expectedCode = ErrorEnvelope.Code.INVALID_CREDENTIALS;
        String expectedMessage = "Invalid credentials.";

        for (String body : scenarios) {
            mockMvc.perform(post(LOGIN_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(expectedCode))
                    .andExpect(jsonPath("$.message").value(expectedMessage));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String json(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(email, password));
    }

    private static JWTClaimsSet parseClaimsUnchecked(String jwt) {
        try {
            return SignedJWT.parse(jwt).getJWTClaimsSet();
        } catch (ParseException e) {
            throw new RuntimeException("Failed to parse JWT claims", e);
        }
    }
}
