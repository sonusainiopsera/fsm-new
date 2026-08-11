package com.fieldservice.workorder.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /api/v1/work-orders/{id}/parts} and
 * {@code POST /api/v1/work-orders/{id}/parts/returns} (WO-149).
 *
 * <p>Fixture data is loaded by V100 (inventory baseline) and V109 (parts-consumption fixtures).
 *
 * <p>Key IDs:
 * <ul>
 *   <li>WO_PARTS_ID: 31000000-...-001 — IN_PROGRESS, TECH_1</li>
 *   <li>WO_COMPLETED_ID: 31000000-...-002 — COMPLETED, TECH_1 (should return 409)</li>
 *   <li>TECH_1 Van A: 60000000-...-011, part PN-002 qty=4, part PN-003 qty=2 (low)</li>
 *   <li>part PN-004 at Van A: qty=0 (insufficient)</li>
 * </ul>
 */
@DisplayName("WorkOrderPartsController integration tests (WO-149)")
class WorkOrderPartsIT extends AbstractIntegrationTest {

    private static final String WO_PARTS_ID     = "31000000-0000-0000-0000-000000000001";
    private static final String WO_COMPLETED_ID = "31000000-0000-0000-0000-000000000002";
    private static final String LOC_VAN_A       = "60000000-0000-0000-0000-000000000011";
    private static final String PART_PN002      = "50000000-0000-0000-0000-000000000002";
    private static final String PART_PN003      = "50000000-0000-0000-0000-000000000003";
    private static final String PART_PN004      = "50000000-0000-0000-0000-000000000004";
    private static final String LOC_VAN_A_TECH2 = "60000000-0000-0000-0000-000000000014";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String url(String woId) {
        return "/api/v1/work-orders/" + woId + "/parts";
    }

    private String returnsUrl(String woId) {
        return "/api/v1/work-orders/" + woId + "/parts/returns";
    }

    // ── 401 unauthenticated ───────────────────────────────────────────────────

    @Test
    @DisplayName("POST /parts requires authentication")
    void consumeParts_requiresAuth() throws Exception {
        mockMvc.perform(post(url(WO_PARTS_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN002, "quantity", 1, "reasonCode", "USED_ON_JOB")))))
                .andExpect(status().isUnauthorized());
    }

    // ── successful consumption ────────────────────────────────────────────────

    @Test
    @DisplayName("TECH_1 can consume parts from own Van A against their IN_PROGRESS work order")
    void consumeParts_tech1_inProgress_success() throws Exception {
        mockMvc.perform(post(url(WO_PARTS_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN002, "quantity", 1, "reasonCode", "USED_ON_JOB")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workOrderId", is(WO_PARTS_ID)))
                .andExpect(jsonPath("$.loggedLines", hasSize(1)))
                .andExpect(jsonPath("$.loggedLines[0].quantity", is(1)))
                .andExpect(jsonPath("$.reconciliationStatus", is("PENDING")));
    }

    // ── insufficient stock (422) ──────────────────────────────────────────────

    @Test
    @DisplayName("422 INSUFFICIENT_STOCK with per-line field error when stock is insufficient")
    void consumeParts_insufficientStock_returns422() throws Exception {
        mockMvc.perform(post(url(WO_PARTS_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN004, "quantity", 5, "reasonCode", "USED_ON_JOB")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("INSUFFICIENT_STOCK")))
                .andExpect(jsonPath("$.fieldErrors", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }

    @Test
    @DisplayName("422 reports all offending lines in multi-line request")
    void consumeParts_multilineInsufficientStock_reportsAllLines() throws Exception {
        mockMvc.perform(post(url(WO_PARTS_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN003, "quantity", 99, "reasonCode", "USED_ON_JOB"),
                                Map.of("partId", PART_PN004, "quantity", 5, "reasonCode", "USED_ON_JOB")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("INSUFFICIENT_STOCK")))
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)));
    }

    // ── illegal work order state (409) ────────────────────────────────────────

    @Test
    @DisplayName("409 when work order is in COMPLETED state")
    void consumeParts_completedWorkOrder_returns409() throws Exception {
        mockMvc.perform(post(url(WO_COMPLETED_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN002, "quantity", 1, "reasonCode", "USED_ON_JOB")))))
                .andExpect(status().isConflict());
    }

    // ── cross-technician 403 ──────────────────────────────────────────────────

    @Test
    @DisplayName("TECH_2 cannot consume from TECH_1's Van A location — 403 with no existence disclosure")
    void consumeParts_tech2OnTech1Location_returns403() throws Exception {
        mockMvc.perform(post(url(WO_PARTS_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.tech2Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN002, "quantity", 1, "reasonCode", "USED_ON_JOB")))))
                .andExpect(status().isForbidden());
    }

    // ── CUSTOMER denied outright ──────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER is denied outright with 403")
    void consumeParts_customer_returns403() throws Exception {
        mockMvc.perform(post(url(WO_PARTS_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.customerJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN002, "quantity", 1, "reasonCode", "USED_ON_JOB")))))
                .andExpect(status().isForbidden());
    }

    // ── validation (400) ──────────────────────────────────────────────────────

    @Test
    @DisplayName("400 when lines array is empty")
    void consumeParts_emptyLines_returns400() throws Exception {
        mockMvc.perform(post(url(WO_PARTS_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400 when quantity is zero")
    void consumeParts_zeroQuantity_returns400() throws Exception {
        mockMvc.perform(post(url(WO_PARTS_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN002, "quantity", 0, "reasonCode", "USED_ON_JOB")))))
                .andExpect(status().isBadRequest());
    }

    // ── successful return ─────────────────────────────────────────────────────

    @Test
    @DisplayName("TECH_1 can return parts back to own Van A")
    void returnParts_tech1_success() throws Exception {
        mockMvc.perform(post(returnsUrl(WO_PARTS_ID))
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consumeBody(LOC_VAN_A, List.of(
                                Map.of("partId", PART_PN002, "quantity", 1, "reasonCode", "UNUSED")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workOrderId", is(WO_PARTS_ID)))
                .andExpect(jsonPath("$.loggedLines", hasSize(1)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String consumeBody(String locationId, List<Map<String, Object>> lines) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("locationId", locationId, "lines", lines));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
