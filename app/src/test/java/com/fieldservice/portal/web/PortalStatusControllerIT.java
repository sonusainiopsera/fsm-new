package com.fieldservice.portal.web;

import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for GET /api/v1/portal/service-requests/{id}/status (WO-171).
 *
 * <p>Uses Testcontainers PostgreSQL via {@link AbstractIntegrationTest} with V100, V118, V125 fixtures.
 * All lifecycle-state branches are covered via the V125 fixtures (AC-10).
 *
 * <p>Tests are transactional (fixture work orders are rolled back per-test to preserve isolation).
 * The ETag 304 test performs two sequential requests within the same test method.
 */
class PortalStatusControllerIT extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/v1/portal/service-requests";

    // V100 fixture: WO_A1 (ASSIGNED) owned by ACCT_A — portal user A can access it
    private static final UUID WO_A1 = UUID.fromString("30000000-0000-0000-0000-000000000001");
    // V100 fixture: WO_B1 (ASSIGNED) owned by ACCT_B — portal user A must NOT access
    private static final UUID WO_B1 = UUID.fromString("30000000-0000-0000-0000-000000000003");

    // V125 fixtures — all states for ACCT_A
    private static final UUID WO_NEW         = UUID.fromString("e0000000-0000-0000-0000-000000000001");
    private static final UUID WO_ASSIGNED    = UUID.fromString("e0000000-0000-0000-0000-000000000002");
    private static final UUID WO_EN_ROUTE    = UUID.fromString("e0000000-0000-0000-0000-000000000003");
    private static final UUID WO_IN_PROGRESS = UUID.fromString("e0000000-0000-0000-0000-000000000004");
    private static final UUID WO_ON_HOLD     = UUID.fromString("e0000000-0000-0000-0000-000000000005");
    private static final UUID WO_COMPLETED   = UUID.fromString("e0000000-0000-0000-0000-000000000006");
    private static final UUID WO_CLOSED      = UUID.fromString("e0000000-0000-0000-0000-000000000007");
    private static final UUID WO_CANCELLED   = UUID.fromString("e0000000-0000-0000-0000-000000000008");

    private static final UUID RANDOM_UUID = UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff");

    @Autowired
    MockMvc mockMvc;

    // ── Happy path — 200 payload shape (AC-1, AC-2) ──────────────────────────

    @Test
    @DisplayName("200 response has correct envelope and all required fields (AC-1)")
    void status_happyPath_returns200WithCorrectShape() throws Exception {
        mockMvc.perform(get(url(WO_A1))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, org.hamcrest.Matchers.matchesRegex("\"[0-9a-f]{64}\"")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=60, must-revalidate"))
                .andExpect(jsonPath("$.data.workOrderId").value(WO_A1.toString()))
                .andExpect(jsonPath("$.data.statusLabel").isString())
                .andExpect(jsonPath("$.data.statusDescription").isString())
                .andExpect(jsonPath("$.data.milestones").isArray())
                .andExpect(jsonPath("$.data.freshness.observedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.freshness.staleAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.freshness.degraded").isBoolean());
    }

    @Test
    @DisplayName("Forbidden fields are absent from the response (AC-2)")
    void status_forbiddenFieldsAbsent() throws Exception {
        String body = mockMvc.perform(get(url(WO_A1))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // GPS / location data
        assertThat(body).doesNotContain("latitude");
        assertThat(body).doesNotContain("longitude");
        // Internal state enum raw values
        assertThat(body).doesNotContain("\"ASSIGNED\"");
        // Technician PII beyond first name + role
        assertThat(body).doesNotContain("phone");
        assertThat(body).doesNotContain("email");
        assertThat(body).doesNotContain("employeeNo");
        assertThat(body).doesNotContain("employeeId");
        // Dispatch internals
        assertThat(body).doesNotContain("score");
        assertThat(body).doesNotContain("dispatch");
        assertThat(body).doesNotContain("overrideReason");
    }

    // ── All lifecycle states produce plain-language labels (AC-7) ────────────

    @Test @DisplayName("NEW state returns 'Request Received' label")
    void status_stateNew_returnsCorrectLabel() throws Exception {
        mockMvc.perform(get(url(WO_NEW))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("Request Received"));
    }

    @Test @DisplayName("ASSIGNED state returns 'Technician Assigned' label")
    void status_stateAssigned_returnsCorrectLabel() throws Exception {
        mockMvc.perform(get(url(WO_ASSIGNED))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("Technician Assigned"));
    }

    @Test @DisplayName("EN_ROUTE state returns 'Technician En Route' label")
    void status_stateEnRoute_returnsCorrectLabel() throws Exception {
        mockMvc.perform(get(url(WO_EN_ROUTE))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("Technician En Route"));
    }

    @Test @DisplayName("IN_PROGRESS state returns 'Work in Progress' label")
    void status_stateInProgress_returnsCorrectLabel() throws Exception {
        mockMvc.perform(get(url(WO_IN_PROGRESS))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("Work in Progress"));
    }

    @Test @DisplayName("ON_HOLD state returns 'Work Paused' with enriched description from hold reason")
    void status_stateOnHold_returnsEnrichedDescription() throws Exception {
        mockMvc.perform(get(url(WO_ON_HOLD))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("Work Paused"))
                .andExpect(jsonPath("$.data.statusDescription").value(
                        org.hamcrest.Matchers.containsString("Awaiting parts")));
    }

    @Test @DisplayName("COMPLETED state returns 'Work Complete' label")
    void status_stateCompleted_returnsCorrectLabel() throws Exception {
        mockMvc.perform(get(url(WO_COMPLETED))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("Work Complete"));
    }

    @Test @DisplayName("CLOSED state returns 'Closed' label")
    void status_stateClosed_returnsCorrectLabel() throws Exception {
        mockMvc.perform(get(url(WO_CLOSED))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("Closed"));
    }

    @Test @DisplayName("CANCELLED state returns 'Cancelled' label")
    void status_stateCancelled_returnsCorrectLabel() throws Exception {
        mockMvc.perform(get(url(WO_CANCELLED))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusLabel").value("Cancelled"));
    }

    // ── ETag conditional GET (AC-4) ───────────────────────────────────────────

    @Test
    @DisplayName("Repeat request with matching If-None-Match returns 304 with empty body (AC-4)")
    void status_etagHit_returns304() throws Exception {
        // First request to get the ETag
        MvcResult first = mockMvc.perform(get(url(WO_NEW))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andReturn();

        String eTag = first.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(eTag).isNotBlank();

        // Second request with matching ETag
        mockMvc.perform(get(url(WO_NEW))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .header(HttpHeaders.IF_NONE_MATCH, eTag))
                .andExpect(status().isNotModified())
                .andExpect(jsonPath("$").doesNotExist());
    }

    @Test
    @DisplayName("Request with non-matching If-None-Match returns 200 with full body (AC-4)")
    void status_etagMiss_returns200() throws Exception {
        mockMvc.perform(get(url(WO_NEW))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .header(HttpHeaders.IF_NONE_MATCH, "\"000000000000000000000000000000000000000000000000000000000000abcd\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrderId").value(WO_NEW.toString()));
    }

    @Test
    @DisplayName("Malformed If-None-Match (wildcard *) is treated as cache miss — no 500 (edge case)")
    void status_wildcardIfNoneMatch_returns200() throws Exception {
        mockMvc.perform(get(url(WO_NEW))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .header(HttpHeaders.IF_NONE_MATCH, "*"))
                .andExpect(status().isOk());
    }

    // ── Ownership isolation (AC-6) ────────────────────────────────────────────

    @Test
    @DisplayName("404 for work order belonging to another account — identical to random UUID (AC-6)")
    void status_foreignAccount_returns404() throws Exception {
        mockMvc.perform(get(url(WO_B1))  // belongs to ACCT_B
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))  // portal user A (ACCT_A)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTAL_RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("404 for non-existent UUID — same response as foreign account (AC-6)")
    void status_nonExistentId_returns404() throws Exception {
        mockMvc.perform(get(url(RANDOM_UUID))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PORTAL_RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("404 for orphan user (no portal_account_user row) — same response as foreign account (AC-6)")
    void status_orphanUser_returns404() throws Exception {
        mockMvc.perform(get(url(WO_A1))
                .with(jwt().jwt(TestJwtFactory.portalOrphanJwt())))
                .andExpect(status().isNotFound());
    }

    // ── Role restriction (AC-6) ───────────────────────────────────────────────

    @Test
    @DisplayName("403 for DISPATCHER principal (AC-6)")
    void status_dispatcherRole_returns403() throws Exception {
        mockMvc.perform(get(url(WO_A1))
                .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("403 for TECHNICIAN principal (AC-6)")
    void status_technicianRole_returns403() throws Exception {
        mockMvc.perform(get(url(WO_A1))
                .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("401 for unauthenticated request")
    void status_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(url(WO_A1)))
                .andExpect(status().isUnauthorized());
    }

    // ── Freshness contract (AC-5) ─────────────────────────────────────────────

    @Test
    @DisplayName("Response always contains freshness object with staleAfterSeconds=60 (AC-5)")
    void status_freshnessPresentAndCorrect() throws Exception {
        mockMvc.perform(get(url(WO_NEW))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.freshness.staleAfterSeconds").value(60))
                .andExpect(jsonPath("$.data.freshness.observedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.freshness.degraded").value(false));
    }

    // ── SLA deadlines (AC-1) ──────────────────────────────────────────────────

    @Test
    @DisplayName("SLA deadlines respondByAt and resolveByAt are present in the response (AC-1)")
    void status_slaDeadlinesPresent() throws Exception {
        mockMvc.perform(get(url(WO_NEW))
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.respondByAt").isNotEmpty())
                .andExpect(jsonPath("$.data.resolveByAt").isNotEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String url(UUID id) {
        return BASE_URL + "/" + id + "/status";
    }
}
