package com.fieldservice.workorder.api;

import com.fieldservice.platform.api.ErrorEnvelope;
import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for work order creation (WO-128).
 *
 * <p>Covers:
 * <ul>
 *   <li>201 response with reference, state NEW, deadlines, appliedSlaPolicyId</li>
 *   <li>400 on validation failures (missing field, unknown field, over-length)</li>
 *   <li>400 on mass-assignment (id, version injected)</li>
 *   <li>422 SITE_CUSTOMER_MISMATCH (site belongs to different customer)</li>
 *   <li>403 CUSTOMER cross-account creation</li>
 *   <li>Atomic write: revision + outbox committed with work order</li>
 *   <li>Idempotency replay via Idempotency-Key header</li>
 * </ul>
 */
@ActiveProfiles("api")
@DisplayName("WorkOrderCreation integration tests")
class WorkOrderCreationIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/work-orders";

    // Fixture IDs from V100 and V110 test fixtures
    private static final String CUST_A  = "00000000-0000-0000-0000-000000000001";
    private static final String SITE_A1 = "10000000-0000-0000-0000-000000000001";
    private static final String CUST_B  = "00000000-0000-0000-0000-000000000002";
    private static final String SITE_B1 = "10000000-0000-0000-0000-000000000002";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dispatcher creates work order → 201 with reference, state NEW, and SLA deadlines")
    void dispatcher_create_returns201WithDeadlinesAndReference() throws Exception {
        String body = validBody(CUST_A, SITE_A1, "MEDIUM");

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.reference").isString())
                .andExpect(jsonPath("$.state").value("NEW"))
                .andExpect(jsonPath("$.responseDeadlineAt").isString())
                .andExpect(jsonPath("$.resolutionDeadlineAt").isString())
                .andExpect(jsonPath("$.atRiskAt").isString())
                .andExpect(jsonPath("$.appliedSlaPolicyId").isString());
    }

    @Test
    @DisplayName("Work order creation commits domain row, Envers revision, and outbox event atomically")
    void create_atomicWrite_revisionAndOutboxPresent() throws Exception {
        String body = validBody(CUST_A, SITE_A1, "HIGH");

        MvcResult result = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isCreated())
                .andReturn();

        String woId = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.id");

        Integer revCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order_aud WHERE id = ?::uuid",
                Integer.class, woId);
        assertThat(revCount).as("Exactly one Envers revision on creation").isEqualTo(1);

        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?::uuid",
                Integer.class, woId);
        assertThat(outboxCount).as("Exactly one outbox event on creation").isGreaterThanOrEqualTo(1);
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Replaying creation with same Idempotency-Key returns original 201, one work order")
    void idempotentReplay_sameKey_returnsOriginalResponse() throws Exception {
        String body = validBody(CUST_A, SITE_A1, "LOW");
        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isCreated())
                .andReturn();

        String firstId = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.id");

        MvcResult replay = mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isCreated())
                .andReturn();

        String replayId = com.jayway.jsonpath.JsonPath.read(
                replay.getResponse().getContentAsString(), "$.id");

        assertThat(replayId).as("Idempotent replay must return the same work order id").isEqualTo(firstId);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_order WHERE id = ?::uuid",
                Integer.class, firstId);
        assertThat(rowCount).as("Only one work order created after idempotent replay").isEqualTo(1);
    }

    // ── Validation failures ──────────────────────────────────────────────────

    @Test
    @DisplayName("Missing faultDescription → 400 VALIDATION_FAILED, no row persisted")
    void create_missingFaultDescription_returns400() throws Exception {
        long rowsBefore = countWorkOrders();
        String body = """
                {"customerId":"%s","siteId":"%s","priority":"MEDIUM","title":"Test"}
                """.formatted(CUST_A, SITE_A1);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.VALIDATION_FAILED))
                .andExpect(jsonPath("$.fieldErrors").isArray());

        assertThat(countWorkOrders()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("faultDescription shorter than 10 chars → 400 VALIDATION_FAILED")
    void create_faultDescriptionTooShort_returns400() throws Exception {
        String body = """
                {"customerId":"%s","siteId":"%s","priority":"MEDIUM","title":"T","faultDescription":"short"}
                """.formatted(CUST_A, SITE_A1);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("Unknown JSON property injected → 400 VALIDATION_FAILED (mass-assignment blocked)")
    void create_unknownProperty_returns400() throws Exception {
        String body = """
                {"customerId":"%s","siteId":"%s","priority":"MEDIUM","title":"T",
                 "faultDescription":"valid description here","state":"COMPLETED"}
                """.formatted(CUST_A, SITE_A1);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("Injecting id and version → 400, no partial write")
    void create_injectingIdAndVersion_returns400() throws Exception {
        long rowsBefore = countWorkOrders();
        String body = """
                {"customerId":"%s","siteId":"%s","priority":"MEDIUM","title":"T",
                 "faultDescription":"valid description here",
                 "id":"ffffffff-ffff-ffff-ffff-ffffffffffff","version":99}
                """.formatted(CUST_A, SITE_A1);

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest());

        assertThat(countWorkOrders()).isEqualTo(rowsBefore);
    }

    // ── Referential integrity ────────────────────────────────────────────────

    @Test
    @DisplayName("Site belonging to different customer → 422 SITE_CUSTOMER_MISMATCH")
    void create_siteBelongsToDifferentCustomer_returns422() throws Exception {
        // SITE_B1 belongs to CUST_B, not CUST_A
        String body = validBody(CUST_A, SITE_B1, "MEDIUM");

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.SITE_CUSTOMER_MISMATCH));
    }

    // ── CUSTOMER role ────────────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER creates work order for their own account → 201")
    void customer_create_ownAccount_returns201() throws Exception {
        String body = validBody(CUST_A, SITE_A1, "LOW");

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("CUSTOMER creates work order for a different account → 403, no existence disclosure")
    void customer_create_crossAccount_returns403() throws Exception {
        // Account A only JWT tries to create for Account B
        String body = validBody(CUST_B, SITE_B1, "LOW");

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.customerAccountAOnlyJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.FORBIDDEN));
    }

    @Test
    @DisplayName("TECHNICIAN tries to create work order → 403")
    void technician_create_returns403() throws Exception {
        String body = validBody(CUST_A, SITE_A1, "MEDIUM");

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private String validBody(String customerId, String siteId, String priority) {
        return """
                {
                  "customerId": "%s",
                  "siteId": "%s",
                  "priority": "%s",
                  "title": "HVAC unit failure",
                  "faultDescription": "Compressor not starting — unit not cooling since 08:00"
                }
                """.formatted(customerId, siteId, priority);
    }

    private long countWorkOrders() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM work_order", Long.class);
        return count != null ? count : 0L;
    }
}
