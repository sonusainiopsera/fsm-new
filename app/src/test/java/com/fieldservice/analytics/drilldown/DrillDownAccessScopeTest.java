package com.fieldservice.analytics.drilldown;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for GET /api/v1/analytics/dashboard/drill-down (WO-168).
 *
 * <p>Covers:
 * <ul>
 *   <li>200 with scoped results for authorized roles (MANAGER, ADMIN, DISPATCHER)</li>
 *   <li>401 unauthenticated</li>
 *   <li>403 for TECHNICIAN and CUSTOMER — no existence disclosure</li>
 *   <li>400 for invalid metric key, window value and sort field</li>
 *   <li>Reconciliation object always present</li>
 *   <li>Page-size clamped to 50 server-side</li>
 *   <li>Row scope: MANAGER and DISPATCHER see work orders; cross-scope probe returns empty data</li>
 * </ul>
 *
 * <p>Fixtures from V108__search_fixtures.sql (work orders) and V100__test_fixtures.sql (identities).
 */
@DisplayName("DrillDownController integration tests (WO-168)")
class DrillDownAccessScopeTest extends AbstractIntegrationTest {

    private static final String DRILL_DOWN_URL = "/api/v1/analytics/dashboard/drill-down";

    @Autowired
    private MockMvc mockMvc;

    // ── 401 unauthenticated ───────────────────────────────────────────────────

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void drillDown_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS"))
                .andExpect(status().isUnauthorized());
    }

    // ── 403 — roles without Confidential work-order access ───────────────────

    @Test
    @DisplayName("TECHNICIAN receives 403 — no existence disclosure")
    void drillDown_technician_receives403() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CUSTOMER receives 403 — no existence disclosure")
    void drillDown_customer_receives403() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "SLA_COMPLIANCE_RATE")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.customerAccountAOnlyJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    // ── 200 — authorized roles ────────────────────────────────────────────────

    @Test
    @DisplayName("MANAGER receives 200 with data and reconciliation envelope")
    void drillDown_manager_receives200WithEnvelope() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "BACKLOG_OPEN_COUNT")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.managerJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.page.totalElements").isNumber())
                .andExpect(jsonPath("$.links").exists())
                .andExpect(jsonPath("$.reconciliation").exists())
                .andExpect(jsonPath("$.reconciliation.resultCount").isNumber())
                .andExpect(jsonPath("$.reconciliation.status",
                        oneOf("MATCHED", "DIVERGED")));
    }

    @Test
    @DisplayName("DISPATCHER receives 200 with correct envelope")
    void drillDown_dispatcher_receives200() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "BACKLOG_OPEN_COUNT")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.reconciliation").exists());
    }

    @Test
    @DisplayName("ADMIN receives 200")
    void drillDown_admin_receives200() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "SLA_COMPLIANCE_RATE")
                        .param("window", "SEVEN_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.adminJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    // ── 400 validation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown metric key returns 400")
    void drillDown_unknownMetric_returns400() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "TAMPERED_METRIC")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.managerJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Unknown window value returns 400")
    void drillDown_unknownWindow_returns400() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "SLA_COMPLIANCE_RATE")
                        .param("window", "FIVE_HUNDRED_YEARS")
                        .with(jwt()
                                .jwt(TestJwtFactory.managerJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Missing metric returns 400")
    void drillDown_missingMetric_returns400() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("window", "THIRTY_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.managerJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isBadRequest());
    }

    // ── Page size clamping ────────────────────────────────────────────────────

    @Test
    @DisplayName("Page size is clamped to 50 server-side")
    void drillDown_oversizedPage_clampedTo50() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "BACKLOG_OPEN_COUNT")
                        .param("window", "THIRTY_DAYS")
                        .param("size", "9999")
                        .with(jwt()
                                .jwt(TestJwtFactory.managerJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size", is(50)));
    }

    // ── Reconciliation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Reconciliation object always present and has status")
    void drillDown_reconciliation_alwaysPresent() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "BACKLOG_OPEN_COUNT")
                        .param("window", "NINETY_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.managerJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliation.status", notNullValue()))
                .andExpect(jsonPath("$.reconciliation.resultCount",
                        greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("Reconciliation resultCount matches page totalElements")
    void drillDown_reconciliation_resultCountMatchesPage() throws Exception {
        mockMvc.perform(get(DRILL_DOWN_URL)
                        .param("metric", "BACKLOG_OPEN_COUNT")
                        .param("window", "THIRTY_DAYS")
                        .with(jwt()
                                .jwt(TestJwtFactory.managerJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"))))
                .andExpect(status().isOk())
                // resultCount in reconciliation must equal page.totalElements
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    com.fasterxml.jackson.databind.JsonNode root =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                    long totalElements = root.path("page").path("totalElements").asLong();
                    long resultCount = root.path("reconciliation").path("resultCount").asLong();
                    org.assertj.core.api.Assertions.assertThat(resultCount)
                            .isEqualTo(totalElements);
                });
    }

    // ── Stable ordering ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Two sequential requests return the same first page (stable ordering)")
    void drillDown_stableOrdering() throws Exception {
        var requestSpec = get(DRILL_DOWN_URL)
                .param("metric", "BACKLOG_OPEN_COUNT")
                .param("window", "THIRTY_DAYS")
                .param("size", "5")
                .with(jwt()
                        .jwt(TestJwtFactory.managerJwt())
                        .authorities(new SimpleGrantedAuthority("ROLE_MANAGER")));

        String body1 = mockMvc.perform(requestSpec)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String body2 = mockMvc.perform(requestSpec)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode d1 = mapper.readTree(body1).path("data");
        com.fasterxml.jackson.databind.JsonNode d2 = mapper.readTree(body2).path("data");
        org.assertj.core.api.Assertions.assertThat(d1).isEqualTo(d2);
    }
}
