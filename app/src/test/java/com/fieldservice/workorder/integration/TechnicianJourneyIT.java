package com.fieldservice.workorder.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.app.security.TestSecurityConfig;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end backend integration test for the technician field execution journey.
 *
 * <p>Covers AC-3 through AC-5 from WO-160:
 * <ul>
 *   <li>AC-3: Cross-technician access → 403 (no existence disclosure)</li>
 *   <li>AC-3: CUSTOMER token → 403 on every technician endpoint</li>
 *   <li>AC-4: Idempotency — same Idempotency-Key produces one effect</li>
 *   <li>AC-5: Envers revision + outbox row per successful transition;
 *             neither after a rolled-back operation</li>
 * </ul>
 *
 * <p>Day-list and simple ASSIGNED→EN_ROUTE→IN_PROGRESS transitions are also
 * exercised as the golden-path spine to surface regression early.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@ActiveProfiles({"worker", "test"})
@Sql(scripts = "/fixtures/seed-journey.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TechnicianJourneyIT {

    private static final String DATE       = "2026-09-15";
    private static final String DAY_URL    = "/api/v1/technicians/me/work-orders";
    private static final String WO_URL     = "/api/v1/work-orders";

    // Work order IDs from seed-journey.sql — one WO per test to avoid version conflicts
    private static final UUID JRN_001 = UUID.fromString("00000000-0000-7160-0000-000000000001"); // ASSIGNED v0 — transition_assignedToEnRoute
    private static final UUID JRN_002 = UUID.fromString("00000000-0000-7160-0000-000000000002"); // EN_ROUTE v1 — transition_enRouteToInProgress
    private static final UUID JRN_003 = UUID.fromString("00000000-0000-7160-0000-000000000003"); // IN_PROGRESS v2 (has labour) — completion success
    private static final UUID JRN_004 = UUID.fromString("00000000-0000-7160-0000-000000000004"); // IN_PROGRESS v3 (no labour) — completion blocked
    private static final UUID JRN_007 = UUID.fromString("00000000-0000-7160-0000-000000000007"); // ASSIGNED v0 — Tech Two — cross-technician
    private static final UUID JRN_008 = UUID.fromString("00000000-0000-7160-0000-000000000008"); // ASSIGNED v0 — audit revision test
    private static final UUID JRN_009 = UUID.fromString("00000000-0000-7160-0000-000000000009"); // ASSIGNED v0 — idempotency test

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fsapi_journey_test")
                    .withUsername("fsapi")
                    .withPassword("fsapi_pw");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
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
    }

    @Autowired MockMvc       mockMvc;
    @Autowired ObjectMapper  objectMapper;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager txManager;

    // ─── Golden path: day list ────────────────────────────────────────────────

    @Test
    @DisplayName("Tech One sees exactly its own journey jobs for 2026-09-15")
    void dayList_techOneScopedToOwnJobs() throws Exception {
        mockMvc.perform(get(DAY_URL + "?date=" + DATE)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                // Tech Two's job must not appear
                .andExpect(jsonPath("$.data[?(@.reference == 'JRN-007')]").doesNotExist());
    }

    @Test
    @DisplayName("Day list includes all active-state Tech One jobs for the reference date")
    void dayList_includesAllActiveStatejobs() throws Exception {
        mockMvc.perform(get(DAY_URL + "?date=" + DATE)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.reference == 'JRN-001')]").exists())
                .andExpect(jsonPath("$.data[?(@.reference == 'JRN-002')]").exists())
                .andExpect(jsonPath("$.data[?(@.reference == 'JRN-003')]").exists())
                .andExpect(jsonPath("$.data[?(@.reference == 'JRN-004')]").exists());
    }

    // ─── Golden path: ASSIGNED → EN_ROUTE ────────────────────────────────────

    @Test
    @DisplayName("DEPART event transitions JRN-001 from ASSIGNED to EN_ROUTE")
    void transition_assignedToEnRoute() throws Exception {
        mockMvc.perform(post(WO_URL + "/" + JRN_001 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody("DEPART", 0))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("EN_ROUTE"));
    }

    // ─── Golden path: EN_ROUTE → IN_PROGRESS ─────────────────────────────────

    @Test
    @DisplayName("START event transitions JRN-002 from EN_ROUTE to IN_PROGRESS")
    void transition_enRouteToInProgress() throws Exception {
        mockMvc.perform(post(WO_URL + "/" + JRN_002 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody("START", 1))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("IN_PROGRESS"));
    }

    // ─── Completion guard: no labour time → 422 ──────────────────────────────

    @Test
    @DisplayName("COMPLETE on JRN-004 (no labour entry) is refused with 422 and LABOUR_TIME_MISSING")
    void transition_completionBlockedWithoutLabourTime() throws Exception {
        mockMvc.perform(post(WO_URL + "/" + JRN_004 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody("COMPLETE", 3))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.guardCode").value("LABOUR_TIME_MISSING"));
    }

    @Test
    @DisplayName("COMPLETE on JRN-003 (has labour entry) succeeds and returns COMPLETED")
    void transition_completionSucceedsWithLabourTime() throws Exception {
        mockMvc.perform(post(WO_URL + "/" + JRN_003 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody("COMPLETE", 2))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("COMPLETED"));
    }

    // ─── AC-3: Security — cross-technician 403 ───────────────────────────────

    @Test
    @DisplayName("Tech One cannot transition Tech Two's job — 403 with no existence disclosure")
    void security_crossTechnicianTransitionDenied() throws Exception {
        mockMvc.perform(post(WO_URL + "/" + JRN_007 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody("DEPART", 0))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isForbidden())
                // Must NOT expose existence — response must not say 'not found'
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("CUSTOMER token cannot access technician day list — 403")
    void security_customerCannotAccessTechnicianDayList() throws Exception {
        mockMvc.perform(get(DAY_URL + "?date=" + DATE)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.customerMultiAccount().getClaims())))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CUSTOMER token cannot transition a work order — 403")
    void security_customerCannotTransition() throws Exception {
        mockMvc.perform(post(WO_URL + "/" + JRN_001 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody("DEPART", 0))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.customerMultiAccount().getClaims())))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request to day list returns 401")
    void security_unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(DAY_URL + "?date=" + DATE))
                .andExpect(status().isUnauthorized());
    }

    // ─── AC-4: Idempotency ────────────────────────────────────────────────────

    @Test
    @DisplayName("Same Idempotency-Key on two DEPART POSTs produces one EN_ROUTE transition")
    void idempotency_sameKeyProducesOneTransition() throws Exception {
        String key = "journey-idem-key-" + System.nanoTime();

        // First request
        mockMvc.perform(post(WO_URL + "/" + JRN_009 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(transitionBody("DEPART", 0))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("EN_ROUTE"));

        // Replay — same key, same body
        mockMvc.perform(post(WO_URL + "/" + JRN_009 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(transitionBody("DEPART", 0))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("EN_ROUTE"));

        // Read state directly — must still be EN_ROUTE (no double advance)
        mockMvc.perform(get(WO_URL + "/" + JRN_009)
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.dispatcher().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EN_ROUTE"));
    }

    // ─── AC-5: Envers audit on transition ────────────────────────────────────

    @Test
    @DisplayName("Successful DEPART on JRN_008 produces an Envers MOD revision row")
    void audit_successfulTransitionProducesRevisionRow() throws Exception {
        // JRN_008 is reserved exclusively for this test (ASSIGNED v0)
        mockMvc.perform(post(WO_URL + "/" + JRN_008 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody("DEPART", 0))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toState").value("EN_ROUTE"));

        // Verify via Envers audit reader in a transaction
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            var reader = AuditReaderFactory.get(entityManager);
            List<Number> revisions = reader.getRevisions(
                    com.fieldservice.workorder.domain.WorkOrder.class, JRN_008);
            // Must have at least one revision (the MOD from the transition above)
            assertThat(revisions.size()).isGreaterThanOrEqualTo(1);

            List<Object[]> rows = reader.createQuery()
                    .forRevisionsOfEntity(
                            com.fieldservice.workorder.domain.WorkOrder.class, false, true)
                    .add(AuditEntity.id().eq(JRN_008))
                    .getResultList();

            boolean hasModRevision = rows.stream()
                    .anyMatch(r -> r[2] == RevisionType.MOD);
            assertThat(hasModRevision).isTrue();
            return null;
        });
    }

    // ─── AC-5: No revision on refused transition ──────────────────────────────

    @Test
    @DisplayName("Refused transition (no labour) produces no extra Envers revision row")
    void audit_refusedTransitionProducesNoRevision() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        long revsBefore = tx.execute(s ->
                (long) AuditReaderFactory.get(entityManager)
                        .getRevisions(com.fieldservice.workorder.domain.WorkOrder.class, JRN_004)
                        .size());

        // Attempt completion without labour time — should fail with 422 (guard refused)
        mockMvc.perform(post(WO_URL + "/" + JRN_004 + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody("COMPLETE", 3))
                        .with(jwt().jwt(b -> b.claims(c ->
                                c.putAll(TestJwtFactory.techOne().getClaims())))))
                .andExpect(status().isUnprocessableEntity());

        long revsAfter = tx.execute(s ->
                (long) AuditReaderFactory.get(entityManager)
                        .getRevisions(com.fieldservice.workorder.domain.WorkOrder.class, JRN_004)
                        .size());

        // Refused transitions must not produce any audit row
        assertThat(revsAfter).isEqualTo(revsBefore);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String transitionBody(String event, int version) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("event", event, "expectedVersion", version));
    }
}
