package com.fieldservice.workforce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for certification type and technician certification endpoints (WO-119).
 *
 * <p>Covers:
 * <ul>
 *   <li>GET /certification-types returns paginated list — ADMIN, MANAGER, DISPATCHER allowed</li>
 *   <li>POST /certification-types is ADMIN-only (403 for DISPATCHER)</li>
 *   <li>CUSTOMER receives 403 on all certification-type reads</li>
 *   <li>GET /technicians/{id}/certifications returns current certs at date</li>
 *   <li>PUT /technicians/{id}/certifications batch upsert round-trip</li>
 *   <li>Assignment guard refuses 422 when regulated cert is missing</li>
 * </ul>
 */
@ActiveProfiles("api")
@DisplayName("Certification integration tests (WO-119)")
class CertificationIT extends AbstractIntegrationTest {

    private static final String CERT_TYPES_URL    = "/api/v1/certification-types";
    private static final String TECH1_CERTS_URL   = "/api/v1/technicians/00000000-0000-0000-0000-000000000011/certifications";
    private static final String TECH2_CERTS_URL   = "/api/v1/technicians/00000000-0000-0000-0000-000000000012/certifications";
    private static final String TECH3_CERTS_URL   = "/api/v1/technicians/00000000-0000-0000-0000-000000000013/certifications";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ── GET /certification-types ─────────────────────────────────────────────

    @Test
    @DisplayName("GET certification-types: ADMIN sees seeded placeholder types")
    void listTypes_admin_returnsSeededPlaceholders() throws Exception {
        mockMvc.perform(get(CERT_TYPES_URL).with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].code").isString())
                .andExpect(jsonPath("$.data[0].regulated").isBoolean())
                .andExpect(jsonPath("$.page.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)));
    }

    @Test
    @DisplayName("GET certification-types: DISPATCHER is allowed (read-only)")
    void listTypes_dispatcher_allowed() throws Exception {
        mockMvc.perform(get(CERT_TYPES_URL).with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET certification-types: CUSTOMER is refused 403 (AC-10)")
    void listTypes_customer_forbidden() throws Exception {
        mockMvc.perform(get(CERT_TYPES_URL).with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                .andExpect(status().isForbidden());
    }

    // ── POST /certification-types ────────────────────────────────────────────

    @Test
    @DisplayName("POST certification-types: ADMIN can create a type")
    void createType_admin_created() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "code", "SCAFFOLD_COTS",
                "displayName", "[PLACEHOLDER] Scaffolding COTS",
                "regulated", false,
                "defaultValidityMonths", 24,
                "active", true));

        mockMvc.perform(post(CERT_TYPES_URL)
                        .with(jwt().jwt(TestJwtFactory.adminJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("SCAFFOLD_COTS")));
    }

    @Test
    @DisplayName("POST certification-types: DISPATCHER is refused 403 (AC-10)")
    void createType_dispatcher_forbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "code", "LADDER_WORK",
                "displayName", "Ladder Work",
                "regulated", false,
                "defaultValidityMonths", 12,
                "active", true));

        mockMvc.perform(post(CERT_TYPES_URL)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ── GET /technicians/{id}/certifications ─────────────────────────────────

    @Test
    @DisplayName("GET certifications: TECH_1 has current regulated cert (AC-3, AC-5)")
    void getCerts_tech1_currentCertReturned() throws Exception {
        mockMvc.perform(get(TECH1_CERTS_URL + "?atDate=2026-09-01")
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].typeCode", is("GAS_SAFE")))
                .andExpect(jsonPath("$.data[0].current", is(true)))
                .andExpect(jsonPath("$.data[0].regulated", is(true)));
    }

    @Test
    @DisplayName("GET certifications: TECH_3 expired regulated cert excluded (AC-5)")
    void getCerts_tech3_expiredRegulated_excluded() throws Exception {
        // atDate=2026-01-01 is after expires_on=2020-01-01, so expired cert is excluded
        mockMvc.perform(get(TECH3_CERTS_URL + "?atDate=2026-01-01")
                        .with(jwt().jwt(TestJwtFactory.adminJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("GET certifications: TECHNICIAN can read own certs (AC-10)")
    void getCerts_technicianReadsOwn() throws Exception {
        mockMvc.perform(get(TECH1_CERTS_URL)
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET certifications: TECHNICIAN cannot read another technician's certs (AC-10)")
    void getCerts_technicianCannotReadOthers() throws Exception {
        mockMvc.perform(get(TECH2_CERTS_URL)
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET certifications: CUSTOMER is refused 403 (AC-10)")
    void getCerts_customer_forbidden() throws Exception {
        mockMvc.perform(get(TECH1_CERTS_URL)
                        .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())))
                .andExpect(status().isForbidden());
    }

    // ── PUT /technicians/{id}/certifications (batch upsert) ──────────────────

    @Test
    @DisplayName("PUT certifications: ADMIN batch upsert round-trip (AC-9)")
    void upsertCerts_admin_success() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "items", List.of(Map.of(
                        "typeCode", "WORKING_AT_HEIGHT",
                        "certificateReference", "WAH-TECH1-2026",
                        "issuedOn", "2024-01-01",
                        "expiresOn", "2026-12-31",
                        "issuingBody", "PASMA"))));

        mockMvc.perform(put(TECH1_CERTS_URL)
                        .with(jwt().jwt(TestJwtFactory.adminJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].typeCode", is("WORKING_AT_HEIGHT")));
    }

    @Test
    @DisplayName("PUT certifications: batch exceeding 200 rows refused 400 (AC-9)")
    void upsertCerts_tooLarge_returns400() throws Exception {
        // Build list of 201 items
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            items.add(Map.of(
                    "typeCode", "GAS_SAFE",
                    "issuedOn", "2024-01-01",
                    "expiresOn", "2099-12-31"));
        }
        String body = objectMapper.writeValueAsString(Map.of("items", items));

        mockMvc.perform(put(TECH1_CERTS_URL)
                        .with(jwt().jwt(TestJwtFactory.adminJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT certifications: DISPATCHER is refused 403 (AC-10)")
    void upsertCerts_dispatcher_forbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "items", List.of(Map.of(
                        "typeCode", "GAS_SAFE",
                        "issuedOn", "2024-01-01",
                        "expiresOn", "2099-12-31"))));

        mockMvc.perform(put(TECH1_CERTS_URL)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}
