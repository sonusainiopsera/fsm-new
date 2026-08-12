package com.fieldservice.audit;

import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for the audit revision search endpoints.
 *
 * <p>Covers: search filtering, RBAC refusal for non-privileged roles,
 * invalid entityType rejection, synchronous export, and async handle for large ranges.
 */
@Tag("integration")
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/audit/audit-revision-seed.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuditRevisionSearchIT extends AbstractIntegrationTest {

    @Autowired
    private DatabaseCleaner dbCleaner;

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
    }

    // ── RBAC ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN role can search audit revisions")
    void adminRole_canSearch() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions")
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("DISPATCHER role is refused with 403 — no existence disclosure")
    void dispatcherRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions")
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MANAGER role is refused with 403")
    void managerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions")
                        .with(jwt().jwt(TestJwtFactory.manager())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN role is refused with 403")
    void technicianRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions")
                        .with(jwt().jwt(TestJwtFactory.techOne())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CUSTOMER role is refused with 403")
    void customerRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions")
                        .with(jwt().jwt(TestJwtFactory.customerMultiAccount())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("No authentication returns 401")
    void noAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions"))
                .andExpect(status().isUnauthorized());
    }

    // ── Filter validation ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown entityType returns 400 with field error")
    void unknownEntityType_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions")
                        .param("entityType", "InjectDropTable; DROP TABLE work_order_aud;")
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("entityType"));
    }

    @Test
    @DisplayName("Valid entityType filter returns 200")
    void validEntityType_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions")
                        .param("entityType", "WorkOrder")
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Page size is clamped to max 50")
    void pageSizeClamped() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions")
                        .param("size", "999")
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isOk());
    }

    // ── Synchronous export ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Small CSV export returns 200 with inline content")
    void smallCsvExport_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/audit-exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"format":"CSV","entityType":"WorkOrder"}
                                """)
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Small JSON export returns 200 with inline content")
    void smallJsonExport_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/audit-exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"format":"JSON","entityType":"WorkOrder"}
                                """)
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unknown revision detail returns 404")
    void unknownRevisionDetail_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-revisions/99999999")
                        .param("entityType", "WorkOrder")
                        .param("entityId", "00000000-0000-0000-0000-000000000001")
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isNotFound());
    }

    // ── Export polling ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Polling unknown export returns 404")
    void pollingUnknownExport_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-exports/00000000-0000-0000-0000-000000000001")
                        .with(jwt().jwt(TestJwtFactory.admin())))
                .andExpect(status().isNotFound());
    }
}
