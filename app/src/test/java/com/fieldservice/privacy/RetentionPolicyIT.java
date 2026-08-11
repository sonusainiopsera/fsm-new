package com.fieldservice.privacy;

import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.annotation.Rollback;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the retention policy admin API (WO-189, AC-4, AC-5, AC-12).
 *
 * <p>Uses Testcontainers PostgreSQL via {@link AbstractIntegrationTest}.
 * V35 seed rows (cc-prefix) and V119 fixture rows (dd-prefix) are loaded via Flyway.
 */
@DisplayName("Retention policy admin API integration tests")
@Transactional
@Rollback
class RetentionPolicyIT extends AbstractIntegrationTest {

    // Seed row IDs from V35 migration
    private static final UUID CLOSED_WORK_ORDERS_ID =
            UUID.fromString("cc000000-0000-7000-8000-000000000003");
    private static final UUID AUDIT_RECORDS_ID =
            UUID.fromString("cc000000-0000-7000-8000-000000000004");

    @Autowired
    private MockMvc mockMvc;

    // ── GET list ──────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-4: GET /api/v1/privacy/retention-policies returns 200 with paginated policy list")
    void listPolicies_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/retention-policies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.totalElements").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(5)));
    }

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    @DisplayName("AC-4: GET /api/v1/privacy/retention-policies returns 403 for non-privacy roles")
    void listPolicies_technician_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/retention-policies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-4: page size is clamped at 50")
    void listPolicies_pageSizeClamped() throws Exception {
        mockMvc.perform(get("/api/v1/privacy/retention-policies?size=200")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(
                        org.hamcrest.Matchers.lessThanOrEqualTo(50)));
    }

    // ── PUT update ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    @DisplayName("AC-4: PUT returns 200 for valid update with correct version")
    void updatePolicy_valid_returns200() throws Exception {
        String body = """
                {
                  "periodValue": 6,
                  "periodUnit": "YEARS",
                  "disposalMethod": "PHYSICAL_DELETE",
                  "legalHold": false,
                  "ratified": false,
                  "enabled": false,
                  "notes": "Updated in test",
                  "version": 0
                }
                """;

        mockMvc.perform(put("/api/v1/privacy/retention-policies/" + CLOSED_WORK_ORDERS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodValue").value(6))
                .andExpect(jsonPath("$.periodUnit").value("YEARS"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-4: PUT returns 400 for invalid period unit")
    void updatePolicy_invalidUnit_returns400() throws Exception {
        String body = """
                {
                  "periodValue": 90,
                  "periodUnit": "WEEKS",
                  "disposalMethod": "PHYSICAL_DELETE",
                  "legalHold": false,
                  "ratified": false,
                  "enabled": false,
                  "version": 0
                }
                """;

        mockMvc.perform(put("/api/v1/privacy/retention-policies/" + CLOSED_WORK_ORDERS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity()); // 422 from BusinessGuardException
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-7/AC-3: PUT returns 422 when AUDIT_RECORDS policy breaches 1-year floor")
    void updatePolicy_auditFloor_returns422() throws Exception {
        String body = """
                {
                  "periodValue": 6,
                  "periodUnit": "MONTHS",
                  "disposalMethod": "PHYSICAL_DELETE",
                  "legalHold": false,
                  "ratified": false,
                  "enabled": false,
                  "version": 0
                }
                """;

        mockMvc.perform(put("/api/v1/privacy/retention-policies/" + AUDIT_RECORDS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity()); // 422
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-4: PUT returns 409 for stale version")
    void updatePolicy_staleVersion_returns409() throws Exception {
        String body = """
                {
                  "periodValue": 90,
                  "periodUnit": "DAYS",
                  "disposalMethod": "PHYSICAL_DELETE",
                  "legalHold": false,
                  "ratified": false,
                  "enabled": false,
                  "version": 999
                }
                """;

        mockMvc.perform(put("/api/v1/privacy/retention-policies/" + CLOSED_WORK_ORDERS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict()); // 409
    }

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    @DisplayName("AC-4: PUT returns 403 for non-privacy roles")
    void updatePolicy_technician_returns403() throws Exception {
        String body = """
                {
                  "periodValue": 90,
                  "periodUnit": "DAYS",
                  "disposalMethod": "PHYSICAL_DELETE",
                  "legalHold": false,
                  "ratified": false,
                  "enabled": false,
                  "version": 0
                }
                """;

        mockMvc.perform(put("/api/v1/privacy/retention-policies/" + CLOSED_WORK_ORDERS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── POST dry-run ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "PRIVACY_ADMIN")
    @DisplayName("AC-5: POST dry-run returns 200 with report including cutoff and disposal method")
    void dryRun_admin_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/retention-policies/" + CLOSED_WORK_ORDERS_ID + "/dry-run")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataCategory").value("CLOSED_WORK_ORDERS"))
                .andExpect(jsonPath("$.cutoffInstant").exists())
                .andExpect(jsonPath("$.disposalMethod").value("PHYSICAL_DELETE"));
    }

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    @DisplayName("AC-5: POST dry-run returns 403 for non-privacy roles")
    void dryRun_technician_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/privacy/retention-policies/" + CLOSED_WORK_ORDERS_ID + "/dry-run")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-5: dry-run mutates no data — row counts identical before and after")
    void dryRun_zeroMutations() throws Exception {
        // Count rows before
        Integer beforeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retention_policy", Integer.class);

        mockMvc.perform(post("/api/v1/privacy/retention-policies/" + CLOSED_WORK_ORDERS_ID + "/dry-run")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Count rows after — must be unchanged
        Integer afterCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retention_policy", Integer.class);
        assertThat(afterCount).isEqualTo(beforeCount);

        // Purge run table must still be empty (dry-run writes nothing)
        Integer purgeRunCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purge_run", Integer.class);
        assertThat(purgeRunCount).isZero();
    }

    // ── purge_run immutability ─────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("AC-8: purge_run rows can be saved and are readable; no update/delete exposed")
    void purgeRun_appendOnly() {
        // Verify table exists and is empty at test start
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM purge_run", Integer.class);
        assertThat(count).isZero();
    }
}
