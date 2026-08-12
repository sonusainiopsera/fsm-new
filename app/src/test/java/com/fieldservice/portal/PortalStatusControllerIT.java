package com.fieldservice.portal;

import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code GET /api/v1/portal/service-requests/{id}/status}.
 *
 * <p>Tests: 200 happy path, 304 conditional GET, ETag change after state transition,
 * 404 scope isolation (cross-account, unknown id), 403 wrong role,
 * and redaction of forbidden fields.
 */
@Tag("integration")
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/seed-wo171.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PortalStatusControllerIT extends AbstractIntegrationTest {

    static final UUID USER_ACME_ID = UUID.fromString("00000000-0000-7019-8000-000000000001");
    static final UUID ACME_ID      = UUID.fromString("00000000-0000-7012-8000-000000000001");
    static final UUID USER_BLUE_ID = UUID.fromString("00000000-0000-7019-8000-000000000002");
    static final UUID BLUE_ID      = UUID.fromString("00000000-0000-7012-8000-000000000002");

    static final UUID WO_NEW_ID  = UUID.fromString("00000000-0000-7171-8000-000000000001");
    static final UUID WO_HOLD_ID = UUID.fromString("00000000-0000-7171-8000-000000000002");

    @Autowired DatabaseCleaner dbCleaner;
    @Autowired JdbcTemplate    jdbc;

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
    }

    // -------------------------------------------------------------------------
    // AC-1 / AC-6: 200 happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: GET status returns 200 with ETag, Cache-Control, and customer-safe fields")
    void getStatus_200_happyPath() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=60")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("must-revalidate")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.workOrderId").value(WO_NEW_ID.toString()))
                .andExpect(jsonPath("$.reference").value("WO171-NEW"))
                .andExpect(jsonPath("$.statusLabel").value("Request received"))
                .andExpect(jsonPath("$.statusDescription").isNotEmpty())
                .andExpect(jsonPath("$.freshness.staleAfterSeconds").value(60))
                .andExpect(jsonPath("$.freshness.degraded").value(false))
                // Forbidden fields must be absent
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andExpect(jsonPath("$.technicianPhone").doesNotExist())
                .andExpect(jsonPath("$.fullName").doesNotExist())
                .andExpect(jsonPath("$.employeeCode").doesNotExist())
                .andExpect(jsonPath("$.internalState").doesNotExist())
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.accountId").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // AC-4: ETag conditional GET — 304
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: Repeat GET with matching If-None-Match returns 304 with no body")
    void getStatus_304_matchingEtag() throws Exception {
        // First request — capture ETag
        String etag = mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        // Second request with captured ETag
        mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_NEW_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, etag)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")));
    }

    // -------------------------------------------------------------------------
    // AC-4: ETag changes on version bump — new 200
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: GET returns 200 with new ETag after version increments")
    void getStatus_200_afterVersionBump() throws Exception {
        // Capture initial ETag
        String etag1 = mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        // Simulate a state transition — increment version directly in DB
        jdbc.update("UPDATE work_order SET version = version + 1 WHERE id = ?", WO_NEW_ID);

        // Conditional GET with stale ETag → must return 200 with new ETag
        String etag2 = mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_NEW_ID)
                        .header(HttpHeaders.IF_NONE_MATCH, etag1)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        org.assertj.core.api.Assertions.assertThat(etag2).isNotEqualTo(etag1);
    }

    // -------------------------------------------------------------------------
    // AC-5: ON_HOLD with reason → customer-safe hold label
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-5: ON_HOLD AWAITING_PARTS returns customer-safe 'Waiting for parts' label")
    void getStatus_onHold_awaitingPartsLabel() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_HOLD_ID)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").value("Waiting for parts"))
                .andExpect(jsonPath("$.statusLabel").value(not(containsString("AWAITING_PARTS"))));
    }

    // -------------------------------------------------------------------------
    // AC-9: Cross-account scope isolation — 404 not 403
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-9: USER_BLUE cannot see Acme work order — returns 404 (non-disclosing)")
    void getStatus_404_crossAccount() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_BLUE_ID, List.of(BLUE_ID)))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-9: Unknown work order id returns 404")
    void getStatus_404_unknownId() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", UUID.randomUUID())
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // AC-10: Wrong role — 403
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-10: Dispatcher JWT on customer endpoint returns 403")
    void getStatus_403_dispatcherRole() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_NEW_ID)
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-10: Unauthenticated request returns 401")
    void getStatus_401_noAuth() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests/{id}/status", WO_NEW_ID))
                .andExpect(status().isUnauthorized());
    }
}
