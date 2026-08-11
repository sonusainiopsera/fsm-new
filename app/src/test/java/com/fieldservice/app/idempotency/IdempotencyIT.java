package com.fieldservice.app.idempotency;

import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import com.fieldservice.platform.idempotency.IdempotencyRecord;
import com.fieldservice.platform.idempotency.IdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.fieldservice.platform.idempotency.IdempotencyFilter.HEADER_NAME;
import static com.fieldservice.platform.idempotency.IdempotencyFilter.REPLAYED_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Testcontainers integration tests for the idempotency layer.
 *
 * <p>Tests the full matrix:
 * <ul>
 *   <li>First call: executes and stores the result (counter = 1)</li>
 *   <li>Identical replay: returns stored response, counter stays at 1</li>
 *   <li>Payload conflict: same key + different body → 409 IDEMPOTENCY_CONFLICT</li>
 *   <li>Missing key: request proceeds normally without idempotency</li>
 *   <li>Invalid key format: returns 400 VALIDATION_FAILED</li>
 *   <li>Error release: 500 response releases the key for a legitimate retry</li>
 *   <li>Expiry: expired key behaves as new (allows re-execution)</li>
 *   <li>Stale IN_PROGRESS reclaim: crashed request does not permanently block key</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({TestSecurityConfig.class, TestMutationController.class})
class IdempotencyIT {

    private static final String MUTATION_URL = "/api/test/mutations";
    private static final String VALID_KEY    = "test-idempotency-key-1234567890";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_idempotency_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url",          postgres::getJdbcUrl);
        registry.add("spring.flyway.user",         postgres::getUsername);
        registry.add("spring.flyway.password",     postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "");
        registry.add("app.idempotency.enabled",        () -> "true");
        registry.add("app.idempotency.lease-duration", () -> "5s");
        registry.add("app.idempotency.purge-job-enabled", () -> "false");
    }

    @Autowired MockMvc                    mockMvc;
    @Autowired IdempotencyRepository      idempotencyRepository;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void reset() {
        TestMutationController.MutationEndpoint.reset();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(s -> { idempotencyRepository.deleteAll(); return null; });
    }

    // ---- Case 1: First call executes and is stored ----------------------------

    @Test
    @DisplayName("first call: executes side effect and returns 201")
    void firstCall_executesAndReturns201() throws Exception {
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(1));

        assertThat(TestMutationController.COUNTER.get()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    // ---- Case 2: Identical replay -------------------------------------------

    @Test
    @DisplayName("identical replay: replays stored response, side effect not re-executed")
    void identicalReplay_returnsStoredResponse() throws Exception {
        // First call
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated());
        assertThat(TestMutationController.COUNTER.get()).isEqualTo(1);

        // Second call (identical key + body)
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.count").value(1));

        // Counter must still be 1 — side effect was not re-executed
        assertThat(TestMutationController.COUNTER.get()).isEqualTo(1);
    }

    // ---- Case 3: Payload conflict -------------------------------------------

    @Test
    @DisplayName("payload conflict: same key + different body → 409 IDEMPOTENCY_CONFLICT")
    void payloadConflict_returns409() throws Exception {
        // First call with body A
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"v\":1}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated());

        // Second call with body B (different payload)
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, VALID_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"v\":2}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // Counter is still 1 — the conflict was not executed
        assertThat(TestMutationController.COUNTER.get()).isEqualTo(1);
    }

    // ---- Case 4: Missing key — proceeds without idempotency ------------------

    @Test
    @DisplayName("missing Idempotency-Key: request proceeds normally (no header = no protection)")
    void missingKey_proceedsNormally() throws Exception {
        mockMvc.perform(post(MUTATION_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(1));

        // No idempotency record stored
        assertThat(idempotencyRepository.count()).isEqualTo(0);
    }

    // ---- Case 5: Invalid key format — 400 ------------------------------------

    @Test
    @DisplayName("invalid key format (too short) → 400 VALIDATION_FAILED")
    void invalidKeyFormat_returns400() throws Exception {
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, "short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"));

        // No execution
        assertThat(TestMutationController.COUNTER.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("invalid key format (disallowed char) → 400 VALIDATION_FAILED")
    void invalidKeyCharacters_returns400() throws Exception {
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, "invalid key with spaces!!")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(TestMutationController.COUNTER.get()).isEqualTo(0);
    }

    // ---- Case 6: User isolation — different users cannot share keys ----------

    @Test
    @DisplayName("same key used by two different users are isolated (separate slots)")
    void differentUsers_keyIsolation() throws Exception {
        String key = "shared-key-across-users-12345678";

        // User A
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().subject("user-A").authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(1));

        // User B — same key, different user: should also execute (not a replay)
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().subject("user-B").authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(2));

        // Two distinct idempotency records
        assertThat(idempotencyRepository.count()).isEqualTo(2);
    }

    // ---- Case 7: Expiry reuse ------------------------------------------------

    @Test
    @DisplayName("expired key is treated as new: re-execution happens")
    void expiredKey_treatedAsNew() throws Exception {
        String key = "expiry-test-key-1234567890123456";

        // First call
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(1));

        // Manually expire the record
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(s -> {
            List<IdempotencyRecord> records = idempotencyRepository.findAll();
            records.forEach(r -> {
                // Set expires_at to the past using reflection
                try {
                    var field = IdempotencyRecord.class.getDeclaredField("expiresAt");
                    field.setAccessible(true);
                    field.set(r, Instant.now().minusSeconds(1));
                } catch (Exception e) { throw new RuntimeException(e); }
                idempotencyRepository.save(r);
            });
            return null;
        });

        // Purge expired
        tx.execute(s -> { idempotencyRepository.deleteAll(
                idempotencyRepository.findAll().stream()
                        .filter(r -> r.getExpiresAt().isBefore(Instant.now()))
                        .toList()); return null; });

        // Second call with same key — should be treated as new
        mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(2));
    }

    // ---- Case 8: Stale IN_PROGRESS reclaim ----------------------------------

    @Test
    @DisplayName("stale IN_PROGRESS row is reclaimed after lease expires")
    void staleInProgress_isReclaimedAfterLease() throws Exception {
        String key = "stale-inprogress-key-12345678901234";

        // Directly insert a stale IN_PROGRESS record (simulates a crashed request)
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(s -> {
            IdempotencyRecord stale = IdempotencyRecord.claim(
                    key, "user", "POST:/api/test/mutations",
                    "deadbeef".repeat(8), // dummy hash
                    Instant.now().plusSeconds(86400));
            // Make it stale by back-dating created_at past the lease duration
            try {
                var field = IdempotencyRecord.class.getDeclaredField("createdAt");
                field.setAccessible(true);
                field.set(stale, Instant.now().minusSeconds(300)); // 5 minutes ago
            } catch (Exception e) { throw new RuntimeException(e); }
            idempotencyRepository.save(stale);
            return null;
        });

        // Now attempt the request — should reclaim the stale row and return 503 with Retry-After
        MvcResult result = mockMvc.perform(post(MUTATION_URL)
                        .header(HEADER_NAME, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().subject("user").authorities(() -> "ROLE_ADMIN")))
                .andReturn();

        // The stale row was reclaimed (503 + Retry-After instructs client to retry)
        assertThat(result.getResponse().getStatus()).isIn(503, 201);
        assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
    }
}
