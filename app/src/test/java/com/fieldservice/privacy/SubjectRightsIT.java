package com.fieldservice.privacy;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for subject rights endpoints (WO-191).
 * Uses V128 fixtures (dd-prefix customer IDs and DSAR requests).
 */
@Transactional
@Rollback
class SubjectRightsIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Rectification: non-privacy role gets 403 ────────────────────────────

    @Test
    @WithMockUser(roles = "DISPATCHER")
    void rectify_nonPrivacyRole_403() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/subjects/CUSTOMER/dd000000-0000-0000-0000-000000000001/rectifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dsarRequestId":"dd000000-0000-7000-8000-000000000002",
                         "corrections":[{"entityName":"Customer","fieldName":"name","newValue":"x"}]}
                        """))
                .andExpect(status().isForbidden());
    }

    // ── Rectification: unverified DSAR returns 422 ──────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    void rectify_unverifiedDsar_422() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/subjects/CUSTOMER/dd000000-0000-0000-0000-000000000003/rectifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dsarRequestId":"dd000000-0000-7000-8000-000000000004",
                         "corrections":[{"entityName":"Customer","fieldName":"name","newValue":"x"}]}
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Rectification: invalid (non-classified) field skipped ───────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    void rectify_nonClassifiedField_skippedInResponse() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/subjects/CUSTOMER/dd000000-0000-0000-0000-000000000001/rectifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dsarRequestId":"dd000000-0000-7000-8000-000000000002",
                         "corrections":[{"entityName":"Customer","fieldName":"__nonexistent","newValue":"x"}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").isEmpty())
                .andExpect(jsonPath("$.skipped[0].fieldName").value("__nonexistent"));
    }

    // ── Erasure: non-privacy role gets 403 ──────────────────────────────────

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    void erasure_nonPrivacyRole_403() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/subjects/CUSTOMER/dd000000-0000-0000-0000-000000000001/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dsarRequestId":"dd000000-0000-7000-8000-000000000001",
                         "confirmation":"CONFIRM_ERASURE"}
                        """))
                .andExpect(status().isForbidden());
    }

    // ── Erasure: wrong confirmation token returns 422 ────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    void erasure_wrongConfirmation_422() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/subjects/CUSTOMER/dd000000-0000-0000-0000-000000000001/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dsarRequestId":"dd000000-0000-7000-8000-000000000001",
                         "confirmation":"WRONG_TOKEN"}
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Erasure: idempotent for already-erased subject ───────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    void erasure_alreadyErased_202WithCompletedOutcome() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/subjects/CUSTOMER/dd000000-0000-0000-0000-000000000002/erasure")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dsarRequestId":"dd000000-0000-7000-8000-000000000003",
                         "confirmation":"CONFIRM_ERASURE"}
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.subjectType").value("CUSTOMER"));
    }

    // ── GET erasure: not found returns 404 ───────────────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    void getErasure_notFound_404() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/erasures/00000000-dead-beef-dead-000000000000"))
                .andExpect(status().isNotFound());
    }

    // ── GET erasure: returns existing tombstone ──────────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    void getErasure_existing_200WithTombstoneData() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/erasures/dd000000-0000-7000-8000-000000000010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.subjectType").value("CUSTOMER"))
                .andExpect(jsonPath("$.subjectId").value("dd000000-0000-0000-0000-000000000002"));
    }
}
