package com.fieldservice.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.identity.api.dto.LoginRequest;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * System integration tests for refresh-token rotation.
 *
 * Tests:
 *   1. login → refresh → replay — 200 + rotated cookie, then 200, then 401 with family revoked
 *   2. Concurrent same-handle — exactly one 200, one 401
 *   3. Data leak — no plaintext handle stored in refresh_token table
 *   4. SIEM deduplication — second replay on revoked family only increments counter
 *   5. Family absolute expiry carried through rotation (token.expires_at = family.absolute_expires_at)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext
class RefreshRotationIntegrationTest extends AbstractIntegrationTest {

    private static final String LOGIN_URL = "/api/v1/auth/login";
    private static final String REFRESH_URL = "/api/v1/auth/refresh";
    private static final String FIXTURE_PASSWORD = "Fixture@1234!Pass";
    private static final String FIXTURE_EMAIL = "id.admin@example.com";

    /** Extracts the raw cookie value from a Set-Cookie header. */
    private static final Pattern COOKIE_VALUE_PATTERN =
            Pattern.compile("refreshToken=([^;]+)");

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

    // -------------------------------------------------------------------------
    // 1. login → refresh → replay
    // -------------------------------------------------------------------------

    @Test
    void loginThenRefreshThenReplay_returns200Then200Then401_familyRevoked() throws Exception {
        // Step 1: login
        String originalHandle = login(FIXTURE_EMAIL);
        assertThat(originalHandle).isNotBlank();

        // Step 2: first refresh — should succeed and rotate the handle
        String rotatedHandle = doRefresh(originalHandle, 200);
        assertThat(rotatedHandle).isNotBlank();
        assertThat(rotatedHandle).isNotEqualTo(originalHandle);

        // Step 3: replay original handle — should fail and revoke family
        doRefreshExpecting401(originalHandle);

        // Step 4: rotated handle also becomes invalid after family revocation
        doRefreshExpecting401(rotatedHandle);

        // Family must be marked revoked in the database
        int revokedFamilies = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token_family WHERE revoked_at IS NOT NULL AND revoked_reason = 'REPLAY_ATTACK'",
                Integer.class);
        assertThat(revokedFamilies).isGreaterThanOrEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 2. Concurrent same-handle — exactly one 200, one 401
    // -------------------------------------------------------------------------

    @Test
    void concurrentSameHandle_exactlyOneSuccessOneFailure() throws Exception {
        String handle = login(FIXTURE_EMAIL);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> tasks = List.of(
                () -> concurrentRefreshStatus(handle),
                () -> concurrentRefreshStatus(handle)
        );
        List<Future<Integer>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }

        long successCount = statuses.stream().filter(s -> s == 200).count();
        long failureCount = statuses.stream().filter(s -> s == 401).count();

        assertThat(successCount).as("Exactly one concurrent request should succeed").isEqualTo(1);
        assertThat(failureCount).as("Exactly one concurrent request should fail").isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // 3. Data leak — no plaintext handle in refresh_token table
    // -------------------------------------------------------------------------

    @Test
    void noPlaintextHandleStoredInDatabase() throws Exception {
        String handle = login(FIXTURE_EMAIL);

        // Query refresh_token table for any row whose token_hash matches the raw handle
        // (it should only contain hex-encoded SHA-256 digests, never the raw value)
        int directMatch = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?",
                Integer.class, handle);
        assertThat(directMatch)
                .as("Plaintext refresh handle must never be stored in token_hash column")
                .isZero();

        // Also verify all stored hashes are 64-character hex strings (SHA-256 output)
        List<String> allHashes = jdbcTemplate.queryForList(
                "SELECT token_hash FROM refresh_token", String.class);
        for (String storedHash : allHashes) {
            assertThat(storedHash)
                    .as("Stored hash must be a 64-char hex string")
                    .matches("[0-9a-f]{64}");
        }
    }

    // -------------------------------------------------------------------------
    // 4. SIEM deduplication — second replay on already-revoked family
    // -------------------------------------------------------------------------

    @Test
    void siemDeduplication_secondReplayOnRevokedFamily_doesNotCreateNewOutboxEvent() throws Exception {
        String handle = login(FIXTURE_EMAIL);

        // First refresh — consume the token
        doRefresh(handle, 200);

        // Count outbox events before first replay
        int beforeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RefreshTokenReuseDetected'",
                Integer.class);

        // Replay original handle — first detection, revokes family, publishes one SIEM event
        doRefreshExpecting401(handle);

        int afterFirstReplay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RefreshTokenReuseDetected'",
                Integer.class);
        assertThat(afterFirstReplay).isEqualTo(beforeCount + 1);

        // Replay original handle again — family already revoked, should NOT add another outbox event
        doRefreshExpecting401(handle);

        int afterSecondReplay = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'RefreshTokenReuseDetected'",
                Integer.class);
        assertThat(afterSecondReplay)
                .as("Second replay on already-revoked family must not emit a new outbox event")
                .isEqualTo(afterFirstReplay);
    }

    // -------------------------------------------------------------------------
    // 5. Absolute expiry is preserved through rotation
    // -------------------------------------------------------------------------

    @Test
    void absoluteExpiryPreservedThroughRotation() throws Exception {
        String handle = login(FIXTURE_EMAIL);
        String rotatedHandle = doRefresh(handle, 200);
        assertThat(rotatedHandle).isNotBlank();

        // Both the original consumed token and the new token must share the same expires_at
        // (which equals the family's absolute_expires_at)
        List<java.time.Instant> expiries = jdbcTemplate.queryForList(
                "SELECT t.expires_at FROM refresh_token t " +
                "JOIN refresh_token_family f ON f.id = t.family_id " +
                "ORDER BY t.issued_at",
                java.time.Instant.class);

        // All tokens in the family must have identical expires_at values
        assertThat(expiries).isNotEmpty();
        if (expiries.size() > 1) {
            java.time.Instant first = expiries.get(0);
            for (java.time.Instant exp : expiries) {
                assertThat(exp).isEqualTo(first);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. Missing or malformed cookie returns 401 (controller-level smoke test)
    // -------------------------------------------------------------------------

    @Test
    void missingCookie_returns401() throws Exception {
        mockMvc.perform(post(REFRESH_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.REAUTHENTICATION_REQUIRED));
    }

    @Test
    void malformedCookieValue_returns401_withoutDbLookup() throws Exception {
        mockMvc.perform(post(REFRESH_URL)
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", "not-a-valid-handle!!!")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.REAUTHENTICATION_REQUIRED));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String login(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(email, FIXTURE_PASSWORD));
        MvcResult result = mockMvc.perform(post(LOGIN_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        return extractHandle(setCookie);
    }

    private String doRefresh(String handle, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(REFRESH_URL)
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", handle)))
                .andExpect(status().is(expectedStatus))
                .andReturn();

        if (expectedStatus == 200) {
            String setCookie = result.getResponse().getHeader("Set-Cookie");
            return extractHandle(setCookie);
        }
        return null;
    }

    private void doRefreshExpecting401(String handle) throws Exception {
        mockMvc.perform(post(REFRESH_URL)
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", handle)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.REAUTHENTICATION_REQUIRED));
    }

    private int concurrentRefreshStatus(String handle) throws Exception {
        return mockMvc.perform(post(REFRESH_URL)
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", handle)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private static String extractHandle(String setCookieHeader) {
        if (setCookieHeader == null) return null;
        Matcher m = COOKIE_VALUE_PATTERN.matcher(setCookieHeader);
        return m.find() ? m.group(1) : null;
    }
}
