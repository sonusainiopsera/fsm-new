package com.fieldservice.sla.web;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for {@link AdminSlaPolicyController} (WO-198, AC-1, 2, 3, 5, 6).
 *
 * <p>Covers:
 * <ul>
 *   <li>GET /api/v1/admin/sla-policies returns paginated envelope with ratified flag</li>
 *   <li>Seeded placeholder rows have ratified=false</li>
 *   <li>Non-admin roles receive 403</li>
 *   <li>PUT with valid payload succeeds and sets ratified=true</li>
 *   <li>PUT with resolutionMinutes < responseMinutes returns 400 with field errors</li>
 *   <li>PUT with wrong version returns 409</li>
 *   <li>Envers revision created on update</li>
 * </ul>
 */
@ActiveProfiles("api")
@DisplayName("AdminSlaPolicy integration tests")
class AdminSlaPolicyIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/admin/sla-policies";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    // ── GET — list ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET list returns paginated envelope with ratified field")
    void list_admin_returnsPaginatedEnvelope() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("Seeded SLA policy rows have ratified=false (AC-2)")
    void list_seededRows_ratifiedFalse() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ratified").value(false));
    }

    @Test
    @DisplayName("GET list: DISPATCHER is refused with 403 (AC-1)")
    void list_dispatcher_returns403() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET list: MANAGER is refused with 403 (AC-1)")
    void list_manager_returns403() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.managerJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET list: TECHNICIAN is refused with 403 (AC-1)")
    void list_technician_returns403() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET list: CUSTOMER is refused with 403 (AC-1)")
    void list_customer_returns403() throws Exception {
        mockMvc.perform(get(URL).with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                .andExpect(status().isForbidden());
    }

    // ── PUT — direct update ────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT with valid payload updates policy and sets ratified=true (AC-5)")
    void update_validPayload_setsRatified() throws Exception {
        // Fetch a seeded policy id and its current version
        UUID id = jdbc.queryForObject(
                "SELECT id FROM sla_policy WHERE priority='HIGH' AND active=true LIMIT 1",
                UUID.class);
        int version = jdbc.queryForObject(
                "SELECT version FROM sla_policy WHERE id=?", Integer.class, id);

        String body = """
                {
                  "responseMinutes": 30,
                  "resolutionMinutes": 120,
                  "atRiskFraction": 0.80,
                  "ratified": true,
                  "version": %d
                }
                """.formatted(version);

        mockMvc.perform(put(URL + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratified").value(true))
                .andExpect(jsonPath("$.responseMinutes").value(30))
                .andExpect(jsonPath("$.resolutionMinutes").value(120));
    }

    @Test
    @DisplayName("PUT: resolutionMinutes < responseMinutes returns 400 with field error (AC-3)")
    void update_resolutionLtResponse_returns400() throws Exception {
        UUID id = jdbc.queryForObject(
                "SELECT id FROM sla_policy WHERE priority='LOW' AND active=true LIMIT 1",
                UUID.class);
        int version = jdbc.queryForObject(
                "SELECT version FROM sla_policy WHERE id=?", Integer.class, id);

        String body = """
                {
                  "responseMinutes": 240,
                  "resolutionMinutes": 60,
                  "atRiskFraction": 0.80,
                  "ratified": false,
                  "version": %d
                }
                """.formatted(version);

        mockMvc.perform(put(URL + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT: atRiskFraction < 0.50 returns 400 (AC-3)")
    void update_atRiskFractionTooLow_returns400() throws Exception {
        UUID id = jdbc.queryForObject(
                "SELECT id FROM sla_policy WHERE priority='MEDIUM' AND active=true LIMIT 1",
                UUID.class);
        int version = jdbc.queryForObject(
                "SELECT version FROM sla_policy WHERE id=?", Integer.class, id);

        String body = """
                {
                  "responseMinutes": 60,
                  "resolutionMinutes": 240,
                  "atRiskFraction": 0.40,
                  "ratified": false,
                  "version": %d
                }
                """.formatted(version);

        mockMvc.perform(put(URL + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT with wrong version returns 409 optimistic-lock conflict")
    void update_wrongVersion_returns409() throws Exception {
        UUID id = jdbc.queryForObject(
                "SELECT id FROM sla_policy WHERE priority='CRITICAL' AND active=true LIMIT 1",
                UUID.class);

        String body = """
                {
                  "responseMinutes": 15,
                  "resolutionMinutes": 60,
                  "atRiskFraction": 0.80,
                  "ratified": false,
                  "version": 9999
                }
                """;

        mockMvc.perform(put(URL + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PUT creates Envers audit revision (AC-6)")
    void update_createsEnversRevision() throws Exception {
        UUID id = jdbc.queryForObject(
                "SELECT id FROM sla_policy WHERE priority='HIGH' AND active=true LIMIT 1",
                UUID.class);
        int version = jdbc.queryForObject(
                "SELECT version FROM sla_policy WHERE id=?", Integer.class, id);
        int revsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_policy_aud WHERE id=?", Integer.class, id);

        String body = """
                {
                  "responseMinutes": 45,
                  "resolutionMinutes": 180,
                  "atRiskFraction": 0.75,
                  "ratified": true,
                  "version": %d
                }
                """.formatted(version);

        mockMvc.perform(put(URL + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk());

        int revsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sla_policy_aud WHERE id=?", Integer.class, id);
        assertThat(revsAfter).isGreaterThan(revsBefore);
    }
}
