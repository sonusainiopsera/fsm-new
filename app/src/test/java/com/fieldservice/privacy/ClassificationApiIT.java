package com.fieldservice.privacy;

import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for the Classification Admin API (WO-188, AC-7, AC-8, AC-9, AC-11).
 *
 * <p>Covers:
 * <ul>
 *   <li>GET /api/v1/privacy/classifications — 200 paginated envelope, 403 for non-admin roles</li>
 *   <li>PUT /api/v1/privacy/classifications/{id} — 200 success, 400 bad tier, 409 stale version</li>
 *   <li>Page size clamping to max 50</li>
 *   <li>Seed rows from V28 are visible in the response</li>
 * </ul>
 */
@DisplayName("Classification Admin API integration tests (WO-188)")
class ClassificationApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String BASE_URL = "/api/v1/privacy/classifications";

    // Fixed seed row ID from V28 — WorkOrder INTERNAL row
    private static final String WORKORDER_CLASSIFICATION_ID = "dc000000-0000-7000-8000-000000000020";

    // =========================================================================
    // GET /api/v1/privacy/classifications
    // =========================================================================

    @Test
    @DisplayName("PRIVACY_ADMIN lists classifications — paginated envelope with seed rows")
    void list_privacyAdmin_returnsPaginatedEnvelope() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.privacyAdminJwt()))
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.page.number", is(0)))
                .andExpect(jsonPath("$.page.size", is(20)))
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("ADMIN role also grants access to classification list")
    void list_admin_returns200() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.adminJwt()))
                        .param("page", "0")
                        .param("size", "5"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DISPATCHER receives 403 on classification list — no existence disclosure")
    void list_dispatcher_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN receives 403 on classification list")
    void list_technician_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MANAGER receives 403 on classification list")
    void list_manager_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.managerJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CUSTOMER receives 403 on classification list")
    void list_customer_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.customerBothAccountsJwt())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Page size above 50 is clamped to 50")
    void list_pageSizeAbove50_isClamped() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.privacyAdminJwt()))
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size", is(50)));
    }

    @Test
    @DisplayName("Tier filter RESTRICTED returns only restricted rows")
    void list_tierFilter_returnsFilteredRows() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.privacyAdminJwt()))
                        .param("tier", "RESTRICTED")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].tier").value(
                        org.hamcrest.Matchers.everyItem(is("RESTRICTED"))));
    }

    @Test
    @DisplayName("Response records include required fields (id, entityName, tier, version)")
    void list_recordContainsRequiredFields() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .with(jwt().jwt(TestJwtFactory.privacyAdminJwt()))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id", notNullValue()))
                .andExpect(jsonPath("$.data[0].entityName", notNullValue()))
                .andExpect(jsonPath("$.data[0].tier", notNullValue()))
                .andExpect(jsonPath("$.data[0].version", notNullValue()));
    }

    // =========================================================================
    // PUT /api/v1/privacy/classifications/{id}
    // =========================================================================

    @Test
    @DisplayName("PUT with valid tier and version returns 200 with updated row")
    void put_validRequest_returns200() throws Exception {
        mockMvc.perform(put(BASE_URL + "/" + WORKORDER_CLASSIFICATION_ID)
                        .with(jwt().jwt(TestJwtFactory.privacyAdminJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tier": "INTERNAL",
                                  "lawfulBasisNote": "Internal operational data",
                                  "handlingNotes": "Role-scoped access",
                                  "version": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier", is("INTERNAL")))
                .andExpect(jsonPath("$.id", is(WORKORDER_CLASSIFICATION_ID)));
    }

    @Test
    @DisplayName("PUT with unknown tier returns 400 with field error")
    void put_unknownTier_returns400() throws Exception {
        mockMvc.perform(put(BASE_URL + "/" + WORKORDER_CLASSIFICATION_ID)
                        .with(jwt().jwt(TestJwtFactory.privacyAdminJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tier": "TOP_SECRET",
                                  "version": 0
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT with stale version returns 409")
    void put_staleVersion_returns409() throws Exception {
        mockMvc.perform(put(BASE_URL + "/" + WORKORDER_CLASSIFICATION_ID)
                        .with(jwt().jwt(TestJwtFactory.privacyAdminJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tier": "INTERNAL",
                                  "version": 9999
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PUT with unknown id returns 404")
    void put_unknownId_returns404() throws Exception {
        mockMvc.perform(put(BASE_URL + "/00000000-0000-0000-0000-000000000000")
                        .with(jwt().jwt(TestJwtFactory.privacyAdminJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tier": "INTERNAL",
                                  "version": 0
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DISPATCHER receives 403 on PUT — no existence disclosure")
    void put_dispatcher_returns403() throws Exception {
        mockMvc.perform(put(BASE_URL + "/" + WORKORDER_CLASSIFICATION_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tier": "INTERNAL", "version": 0}
                                """))
                .andExpect(status().isForbidden());
    }
}
