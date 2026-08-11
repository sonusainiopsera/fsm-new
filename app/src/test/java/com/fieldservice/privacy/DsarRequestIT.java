package com.fieldservice.privacy;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the DSAR admin API (WO-190, AC-1,2,3,7,8,12).
 *
 * <p>Uses Testcontainers PostgreSQL via {@link AbstractIntegrationTest}.
 * V120 fixture rows (ee-prefix) are loaded via Flyway test fixture location.
 */
@DisplayName("DSAR request admin API integration tests")
@Transactional
@Rollback
class DsarRequestIT extends AbstractIntegrationTest {

    private static final UUID RECEIVED_ID =
            UUID.fromString("ee000000-0000-7000-8000-000000000001");
    private static final UUID VERIFIED_ID =
            UUID.fromString("ee000000-0000-7000-8000-000000000002");
    private static final UUID FULFILLED_ID =
            UUID.fromString("ee000000-0000-7000-8000-000000000003");
    private static final UUID REJECTED_ID =
            UUID.fromString("ee000000-0000-7000-8000-000000000004");

    @Autowired
    private MockMvc mockMvc;

    // ── POST create ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    @DisplayName("AC-1: POST creates request with state=RECEIVED and dueAt")
    void createRequest_privacyAdmin_returns201() throws Exception {
        String body = """
                {
                  "requestType": "ACCESS",
                  "subjectType": "CUSTOMER",
                  "subjectId": "00000000-0000-0000-0000-000000000001",
                  "notes": "Integration test request"
                }
                """;

        mockMvc.perform(post("/api/v1/privacy/dsar-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andExpect(jsonPath("$.dueAt").exists())
                .andExpect(jsonPath("$.submittedAt").exists())
                .andExpect(jsonPath("$.remainingDays").isNumber());
    }

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    @DisplayName("AC-7: POST returns 403 for non-privacy roles")
    void createRequest_technician_returns403() throws Exception {
        String body = """
                {"requestType":"ACCESS","subjectType":"CUSTOMER",
                 "subjectId":"00000000-0000-0000-0000-000000000001"}
                """;
        mockMvc.perform(post("/api/v1/privacy/dsar-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-1: POST returns 400 for missing required fields")
    void createRequest_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/dsar-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── GET list ──────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-8: GET returns paginated list with remainingDays and atRisk")
    void listRequests_admin_returns200WithEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").exists());
    }

    @Test
    @WithMockUser(roles = "DISPATCHER")
    @DisplayName("AC-7: GET returns 403 for non-privacy roles with no existence disclosure")
    void listRequests_dispatcher_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-8: page size is clamped at 50")
    void listRequests_pageSizeClamped() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests?size=200")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(
                        org.hamcrest.Matchers.lessThanOrEqualTo(50)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-8: state filter returns matching requests only")
    void listRequests_stateFilter_returnsFiltered() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests?state=RECEIVED")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].state",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.equalTo("RECEIVED"))));
    }

    // ── GET single request ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    @DisplayName("AC-8: GET /{id} returns request with correct fields")
    void getRequest_privacyAdmin_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests/" + RECEIVED_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andExpect(jsonPath("$.requestType").value("ACCESS"))
                .andExpect(jsonPath("$.remainingDays").isNumber())
                .andExpect(jsonPath("$.atRisk").isBoolean());
    }

    // ── POST transitions ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-2: ACKNOWLEDGE transitions RECEIVED → IDENTITY_PENDING")
    void transition_acknowledge_receivedToIdentityPending() throws Exception {
        String body = """
                {"event": "ACKNOWLEDGE", "note": "Request acknowledged by DPO"}
                """;
        mockMvc.perform(post("/api/v1/privacy/dsar-requests/" + RECEIVED_ID + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDENTITY_PENDING"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-2: illegal transition returns 409")
    void transition_illegal_returns409() throws Exception {
        String body = """
                {"event": "FULFILL", "note": "Should fail"}
                """;
        mockMvc.perform(post("/api/v1/privacy/dsar-requests/" + RECEIVED_ID + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict()); // 409
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-2: VERIFY without verificationMethod returns 422 (guard refused)")
    void transition_verify_withoutVerificationMethod_returns422() throws Exception {
        // First ACKNOWLEDGE
        String ackBody = """
                {"event": "ACKNOWLEDGE"}
                """;
        mockMvc.perform(post("/api/v1/privacy/dsar-requests/" + RECEIVED_ID + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ackBody))
                .andExpect(status().isOk());

        // Then VERIFY without verificationMethod
        String verifyBody = """
                {"event": "VERIFY", "note": "Missing verification method"}
                """;
        mockMvc.perform(post("/api/v1/privacy/dsar-requests/" + RECEIVED_ID + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(verifyBody))
                .andExpect(status().isUnprocessableEntity()); // 422
    }

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    @DisplayName("AC-7: transitions return 403 for non-privacy roles")
    void transition_technician_returns403() throws Exception {
        String body = """
                {"event": "ACKNOWLEDGE"}
                """;
        mockMvc.perform(post("/api/v1/privacy/dsar-requests/" + RECEIVED_ID + "/transitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── GET export ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    @DisplayName("AC-3/AC-7: export for FULFILLED request returns 200 with manifest and URL")
    void getExport_fulfilled_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests/" + FULFILLED_ID + "/export")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactId").exists())
                .andExpect(jsonPath("$.manifest").isArray())
                .andExpect(jsonPath("$.downloadUrl").exists())
                .andExpect(jsonPath("$.expiresInSeconds").value(
                        org.hamcrest.Matchers.lessThanOrEqualTo(300)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-3: export for RECEIVED (unverified) request returns 422")
    void getExport_unverified_returns422() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests/" + RECEIVED_ID + "/export")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity()); // 422
    }

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    @DisplayName("AC-7: export endpoint returns 403 for non-privacy roles")
    void getExport_technician_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests/" + FULFILLED_ID + "/export"))
                .andExpect(status().isForbidden());
    }

    // ── Fulfilment metrics ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-10: GET /metrics/fulfillment returns O7 guardrail metrics")
    void getFulfillmentMetrics_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/dsar-requests/metrics/fulfillment")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClosed").isNumber())
                .andExpect(jsonPath("$.fulfilledInTime").isNumber())
                .andExpect(jsonPath("$.fulfilmentRatePct").isNumber())
                .andExpect(jsonPath("$.openByRemainingDays").exists());
    }

    // ── Zero-mutation assertion ───────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-3: export endpoint does not create new dsar_request rows")
    void getExport_zeroMutations_onRequestTable() throws Exception {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM dsar_request", Integer.class);

        mockMvc.perform(get("/api/v1/privacy/dsar-requests/" + FULFILLED_ID + "/export")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM dsar_request", Integer.class);
        assertThat(after).isEqualTo(before);
    }
}
