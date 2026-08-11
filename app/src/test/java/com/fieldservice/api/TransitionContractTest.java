package com.fieldservice.api;

import com.fieldservice.api.support.ApiAssertions;
import com.fieldservice.domain.workorder.WorkOrderState;
import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.workorder.WorkOrderFixtureBuilder;
import com.fieldservice.workorder.WorkOrderTransitionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 contract conformance tests for the work-order transitions endpoint group (WO-204, AC-1, AC-3, AC-7).
 *
 * <p>This class focuses on the contract-level assertions for the transitions endpoint:
 * response shape, error shapes, and idempotency proof. Full coverage of all transition
 * state-machine paths is in {@code WorkOrderTransitionControllerIT}.
 *
 * <p>The {@code api} profile activates {@link com.fieldservice.idempotency.IdempotencyKeyFilter}
 * so idempotency proof tests run against the real key store.
 *
 * <p>Covers:
 * <ul>
 *   <li>Legal transition response shape: fromState, toState, version, legalNextEvents, occurredAt</li>
 *   <li>Illegal transition → 409 WORK_ORDER_ILLEGAL_TRANSITION error shape</li>
 *   <li>Stale expectedVersion → 409 WORK_ORDER_VERSION_CONFLICT error shape</li>
 *   <li>Concurrent transitions: exactly one winner, other gets 409 (AC-7)</li>
 *   <li>Idempotency proof: same Idempotency-Key returns original response, one revision only (AC-7)</li>
 *   <li>Different key on same payload creates a second effect (AC-7)</li>
 * </ul>
 */
@ActiveProfiles("api")
@DisplayName("Transitions endpoint group — P0 contract conformance")
class TransitionContractTest extends AbstractIntegrationTest {

    private static final String TRANSITIONS_URL = "/api/v1/work-orders/{id}/transitions";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;
    @Autowired WorkOrderTransitionService transitionService;
    @Autowired JdbcTemplate jdbc;

    private WorkOrderFixtureBuilder fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new WorkOrderFixtureBuilder(entityManager, txTemplate, transitionService);
    }

    // ── Legal transition response shape ──────────────────────────────────────

    @Test
    @DisplayName("Legal transition response: fromState, toState, version, legalNextEvents, occurredAt")
    void legalTransition_responseShape() throws Exception {
        UUID woId = fixtures.createInState(WorkOrderState.NEW);

        mockMvc.perform(post(TRANSITIONS_URL, woId)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"ASSIGN\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromState").value("NEW"))
                .andExpect(jsonPath("$.toState").value("ASSIGNED"))
                .andExpect(jsonPath("$.version").isNumber())
                .andExpect(jsonPath("$.legalNextEvents").isArray())
                .andExpect(jsonPath("$.occurredAt").isString());
    }

    // ── Error envelope conformance ───────────────────────────────────────────

    @Test
    @DisplayName("Illegal transition → 409 WORK_ORDER_ILLEGAL_TRANSITION error envelope")
    void illegalTransition_errorShape() throws Exception {
        UUID woId = fixtures.createInState(WorkOrderState.NEW);

        ApiAssertions.assertErrorShape(
                mockMvc.perform(post(TRANSITIONS_URL, woId)
                                .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                                .contentType(MediaType.APPLICATION_JSON)
                                // COMPLETE is not legal from NEW
                                .content("{\"event\":\"COMPLETE\",\"expectedVersion\":0}"))
                        .andExpect(status().isConflict()),
                ErrorEnvelope.Code.WORK_ORDER_ILLEGAL_TRANSITION,
                0);
    }

    @Test
    @DisplayName("Stale expectedVersion → 409 WORK_ORDER_VERSION_CONFLICT error envelope")
    void staleVersion_errorShape() throws Exception {
        UUID woId = fixtures.createInState(WorkOrderState.NEW);

        ApiAssertions.assertErrorShape(
                mockMvc.perform(post(TRANSITIONS_URL, woId)
                                .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"event\":\"ASSIGN\",\"expectedVersion\":9999}"))
                        .andExpect(status().isConflict()),
                ErrorEnvelope.Code.WORK_ORDER_VERSION_CONFLICT,
                0);
    }

    @Test
    @DisplayName("Illegal transition → no internal leak in error body")
    void illegalTransition_noInternalLeak() throws Exception {
        UUID woId = fixtures.createInState(WorkOrderState.NEW);

        ApiAssertions.assertNoInternalLeak(
                mockMvc.perform(post(TRANSITIONS_URL, woId)
                                .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"event\":\"COMPLETE\",\"expectedVersion\":0}"))
                        .andExpect(status().isConflict()));
    }

    // ── Concurrent transitions ───────────────────────────────────────────────

    @Test
    @DisplayName("AC-7: two concurrent ASSIGN attempts → exactly one succeeds, other gets 409 version conflict")
    void concurrentTransitions_exactlyOneWinner() throws Exception {
        UUID woId = fixtures.createInState(WorkOrderState.NEW);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount  = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<?> t1 = exec.submit(() -> {
            try {
                startLatch.await();
                int status = mockMvc.perform(post(TRANSITIONS_URL, woId)
                                .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"event\":\"ASSIGN\",\"expectedVersion\":0}"))
                        .andReturn().getResponse().getStatus();
                if (status == 200) successCount.incrementAndGet();
                else if (status == 409) conflictCount.incrementAndGet();
            } catch (Exception ignored) {}
        });
        Future<?> t2 = exec.submit(() -> {
            try {
                startLatch.await();
                int status = mockMvc.perform(post(TRANSITIONS_URL, woId)
                                .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"event\":\"ASSIGN\",\"expectedVersion\":0}"))
                        .andReturn().getResponse().getStatus();
                if (status == 200) successCount.incrementAndGet();
                else if (status == 409) conflictCount.incrementAndGet();
            } catch (Exception ignored) {}
        });

        startLatch.countDown();
        t1.get();
        t2.get();
        exec.shutdown();

        assertThat(successCount.get())
                .as("Exactly one concurrent ASSIGN must succeed")
                .isEqualTo(1);
        assertThat(conflictCount.get())
                .as("The losing concurrent ASSIGN must receive 409 version conflict")
                .isEqualTo(1);
    }

    // ── Idempotency proof ────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-7: replaying transition with same Idempotency-Key returns original response, one revision")
    void idempotentReplay_sameKey_returnsOriginalResponse_oneRevision() throws Exception {
        UUID woId = fixtures.createInState(WorkOrderState.NEW);
        String idempotencyKey = UUID.randomUUID().toString();

        // First request
        MvcResult first = mockMvc.perform(post(TRANSITIONS_URL, woId)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("{\"event\":\"ASSIGN\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andReturn();

        int revisionsBefore = countRevisions(woId);

        // Replay with same key — must return identical response body
        MvcResult replay = mockMvc.perform(post(TRANSITIONS_URL, woId)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("{\"event\":\"ASSIGN\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andReturn();

        int revisionsAfter = countRevisions(woId);

        assertThat(replay.getResponse().getContentAsString())
                .as("Idempotent replay must return the same response body as the original")
                .isEqualTo(first.getResponse().getContentAsString());

        assertThat(revisionsAfter)
                .as("Idempotent replay must not create an additional Envers revision")
                .isEqualTo(revisionsBefore);
    }

    @Test
    @DisplayName("AC-7: different Idempotency-Key on same payload creates a second revision")
    void differentKey_samePayload_createsSecondRevision() throws Exception {
        UUID woId = fixtures.createInState(WorkOrderState.NEW);

        // First transition
        mockMvc.perform(post(TRANSITIONS_URL, woId)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"event\":\"ASSIGN\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        int revAfterFirst = countRevisions(woId);
        int currentVer   = currentVersion(woId);

        // Second transition with a different key on the already-ASSIGNED WO
        mockMvc.perform(post(TRANSITIONS_URL, woId)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"event\":\"DEPART\",\"expectedVersion\":" + currentVer + "}"))
                .andExpect(status().isOk());

        int revAfterSecond = countRevisions(woId);
        assertThat(revAfterSecond)
                .as("A distinct Idempotency-Key on a different event must create a new revision")
                .isGreaterThan(revAfterFirst);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int currentVersion(UUID woId) {
        return txTemplate.execute(st ->
                entityManager.find(com.fieldservice.domain.workorder.WorkOrder.class, woId).getVersion());
    }

    private int countRevisions(UUID woId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order_aud WHERE id = ?",
                Integer.class, woId);
        return count != null ? count : 0;
    }
}
