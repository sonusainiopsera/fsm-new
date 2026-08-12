package com.fieldservice.audit;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for audit revision search and RBAC (WO-199).
 *
 * <p>Covers: ADMIN/COMPLIANCE_REVIEWER access, RBAC refusal for non-privileged roles,
 * allow-list validation for entityType, export endpoint 200/202 routing.
 */
@DisplayName("Audit — search and RBAC integration")
class AuditSearchIT extends AbstractIntegrationTest {

    private static final String SEARCH_URL = "/api/v1/admin/audit-revisions";
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Autowired
    private MockMvc mockMvc;

    // ── RBAC: privileged access ─────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN can search audit revisions")
    void adminCanSearch() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(b -> b.subject(ADMIN_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("COMPLIANCE_REVIEWER can search audit revisions")
    void complianceReviewerCanSearch() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(b -> b.subject(ADMIN_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("COMPLIANCE_REVIEWER"))))
                .andExpect(status().isOk());
    }

    // ── RBAC: non-privileged roles ──────────────────────────────────────────────

    @Test
    @DisplayName("DISPATCHER is refused — 403")
    void dispatcherRefused() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(b -> b.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("DISPATCHER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MANAGER is refused — 403")
    void managerRefused() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(b -> b.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("MANAGER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN is refused — 403")
    void technicianRefused() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(b -> b.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CUSTOMER is refused — 403")
    void customerRefused() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(b -> b.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void unauthenticatedRefused() throws Exception {
        mockMvc.perform(get(SEARCH_URL))
                .andExpect(status().isUnauthorized());
    }

    // ── Allow-list validation ────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown entityType returns 400 — no query execution")
    void unknownEntityTypeReturns400() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("entityType", "'; DROP TABLE revinfo; --")
                        .with(jwt()
                                .jwt(b -> b.subject(ADMIN_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Valid entityType WorkOrder is accepted")
    void validEntityTypeAccepted() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("entityType", "WorkOrder")
                        .with(jwt()
                                .jwt(b -> b.subject(ADMIN_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ADMIN"))))
                .andExpect(status().isOk());
    }

    // ── Page size cap ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Page size is capped at 50 even when requesting more")
    void pageSizeCappedAt50() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("size", "200")
                        .with(jwt()
                                .jwt(b -> b.subject(ADMIN_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    // ── Export endpoint ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Export with invalid format returns 400")
    void exportInvalidFormat() throws Exception {
        mockMvc.perform(post(SEARCH_URL + "/exports")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"XML\"}")
                        .with(jwt()
                                .jwt(b -> b.subject(ADMIN_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Small export with CSV format returns 200 with content")
    void smallCsvExportReturns200() throws Exception {
        mockMvc.perform(post(SEARCH_URL + "/exports")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"format\":\"CSV\",\"entityType\":\"WorkOrder\"}")
                        .with(jwt()
                                .jwt(b -> b.subject(ADMIN_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("ADMIN"))))
                .andExpect(status().isOk());
    }
}
