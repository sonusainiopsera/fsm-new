package com.fieldservice.app.idempotency;

import com.fieldservice.app.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(IdempotencyTestController.class)
class IdempotencyFilterIntegrationTest extends AbstractIntegrationTest {

    private static final String ENDPOINT = "/test/idempotency/increment";
    private static final String USER = "test-user-1";
    private static final String OTHER_USER = "test-user-2";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        IdempotencyTestController.COUNTER.set(0);
        IdempotencyTestController.FAIL_NEXT.set(false);
        jdbc.update("DELETE FROM idempotency_key");
    }

    // AC2, AC9: first call executes and counter increments exactly once
    @Test
    void first_call_executes_and_stores() throws Exception {
        String key = "test-key-first-call-0001";

        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(1));

        assertThat(IdempotencyTestController.COUNTER.get()).isEqualTo(1);
        assertThat(idempotencyRowCount()).isEqualTo(1);
    }

    // AC2, AC9: identical replay returns same status/body; counter stays at 1
    @Test
    void identical_replay_returns_stored_response() throws Exception {
        String key = "test-key-identical-replay-0002";
        String body = "{}";

        // First call
        String firstResponse = mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Identical replay
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(content().json(firstResponse));

        // Counter must not have advanced on replay
        assertThat(IdempotencyTestController.COUNTER.get()).isEqualTo(1);
    }

    // AC3: same key, different payload → 409 IDEMPOTENCY_CONFLICT; counter unchanged
    @Test
    void differing_payload_returns_conflict() throws Exception {
        String key = "test-key-payload-conflict-0003";

        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"first\":true}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"different\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        assertThat(IdempotencyTestController.COUNTER.get()).isEqualTo(1);
    }

    // AC7: missing key → allowed through (requireKey=false), counter increments
    @Test
    void missing_key_allowed_through_by_default() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(1));

        assertThat(idempotencyRowCount()).isEqualTo(0);
    }

    // AC7: invalid key format → 400 with fieldErrors naming the header
    @Test
    void invalid_key_format_returns_validation_error() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", "short")  // < 16 chars
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"));
    }

    @Test
    void key_at_minimum_length_16_is_accepted() throws Exception {
        String key = "a".repeat(16);
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void key_above_128_chars_is_rejected() throws Exception {
        String key = "a".repeat(129);
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"));
    }

    // AC10: 5xx releases the key so a legitimate retry executes on the same endpoint
    @Test
    void error_response_releases_key_for_retry() throws Exception {
        String key = "test-key-error-release-0008";
        String body = "{}";

        // First call: make the controller return 500
        IdempotencyTestController.FAIL_NEXT.set(true);
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isInternalServerError());

        // No row persisted (key released after 5xx)
        assertThat(idempotencyRowCount()).isEqualTo(0);
        assertThat(IdempotencyTestController.COUNTER.get()).isEqualTo(1);

        // Retry with same key on same endpoint: key was released so it executes again
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(header().doesNotExist("Idempotency-Replayed"));
    }

    // AC6: expired key is treated as new — counter increments again
    @Test
    void expired_key_treated_as_new() throws Exception {
        String key = "test-key-expiry-reuse-0009";
        String body = "{}";

        // First call
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated());

        // Force-expire the record
        jdbc.update("UPDATE idempotency_key SET expires_at = ? WHERE idempotency_key = ?",
                Timestamp.from(Instant.now().minusSeconds(10)), key);

        // Retry with same key — should execute fresh
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        assertThat(IdempotencyTestController.COUNTER.get()).isEqualTo(2);
    }

    // AC5: stale IN_PROGRESS row (simulated crash) is reclaimable after lease expiry
    @Test
    void stale_in_progress_is_reclaimable_after_lease() throws Exception {
        String key = "test-key-crash-reclaim-0010";
        String userId = USER;
        String endpoint = "POST:/test/idempotency/increment";

        // Simulate a crashed request: insert an old IN_PROGRESS row
        Instant staleTime = Instant.now().minusSeconds(120); // 2 minutes ago > 30s lease
        jdbc.update(
                "INSERT INTO idempotency_key " +
                "(id, idempotency_key, user_id, endpoint, request_hash, state, created_at, expires_at) " +
                "VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)",
                UUID.randomUUID(), key, userId, endpoint, "aabbcc1122334455aabbcc1122334455aabbcc1122334455aabbcc1122334455",
                Timestamp.from(staleTime), Timestamp.from(staleTime.plusSeconds(86400)));

        // Now issue a real request with the same key — should reclaim and execute
        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        assertThat(IdempotencyTestController.COUNTER.get()).isEqualTo(1);
    }

    // Edge case: same key from different users does not collide
    @Test
    void same_key_different_users_are_independent() throws Exception {
        String key = "test-key-user-isolation-0011";
        String body = "{}";

        mockMvc.perform(post(ENDPOINT)
                .with(user(USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(post(ENDPOINT)
                .with(user(OTHER_USER))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        assertThat(idempotencyRowCount()).isEqualTo(2);
    }

    // AC4: concurrent requests — exactly one executes, loser gets deterministic outcome
    @Test
    void concurrent_duplicate_requests_only_one_executes() throws Exception {
        String key = "test-key-concurrent-0012";
        String body = "{}";
        int threadCount = 5;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Future<Integer>> futures = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        return mockMvc.perform(post(ENDPOINT)
                                .with(user(USER))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    } catch (Exception e) {
                        return -1;
                    } finally {
                        done.countDown();
                    }
                }));
            }
            startGate.countDown();
            done.await();
        } finally {
            pool.shutdown();
        }

        List<Integer> statuses = new ArrayList<>();
        for (var f : futures) {
            statuses.add(f.get());
        }

        // Exactly one 201 (or replays) + all outcomes are deterministic (201 or 409)
        long createdCount = statuses.stream().filter(s -> s == 201).count();
        long conflictOrReplay = statuses.stream().filter(s -> s == 409 || s == 201).count();
        assertThat(createdCount).isGreaterThanOrEqualTo(1);
        assertThat(conflictOrReplay).isEqualTo(threadCount);

        // Counter must not exceed 1 (no double execution)
        assertThat(IdempotencyTestController.COUNTER.get()).isEqualTo(1);
    }

    private int idempotencyRowCount() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_key", Integer.class);
        return count != null ? count : 0;
    }
}
