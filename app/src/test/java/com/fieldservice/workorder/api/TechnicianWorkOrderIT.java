package com.fieldservice.workorder.api;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /api/v1/technicians/me/work-orders} (WO-154).
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy path: 200 with correct work orders for TECH_1 on test day 2026-09-15</li>
 *   <li>Scope isolation: TECH_2's work order not in TECH_1 response</li>
 *   <li>Carry-over: IN_PROGRESS with null window appears</li>
 *   <li>Terminal exclusion: COMPLETED job does not appear</li>
 *   <li>ETag and 304 Not Modified conditional GET</li>
 *   <li>403 for CUSTOMER role</li>
 *   <li>401 for unauthenticated</li>
 *   <li>Page size clamping (size=500 → at most 50 items)</li>
 *   <li>400 for invalid sort field</li>
 * </ul>
 *
 * <p>Fixture data loaded by V133__technician_day_fixtures.sql.
 */
@DisplayName("TechnicianWorkOrderController integration tests (WO-154)")
class TechnicianWorkOrderIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/technicians/me/work-orders";
    private static final String TEST_DATE = "2026-09-15";

    // Fixture IDs from V133
    private static final String WO_TODAY_1  = "cd300000-0000-0000-0000-000000000001";
    private static final String WO_TODAY_2  = "cd300000-0000-0000-0000-000000000002";
    private static final String WO_CARRY_3  = "cd300000-0000-0000-0000-000000000003"; // earlier day
    private static final String WO_CARRY_4  = "cd300000-0000-0000-0000-000000000004"; // null window
    private static final String WO_TECH2_5  = "cd300000-0000-0000-0000-000000000005";
    private static final String WO_DONE_6   = "cd300000-0000-0000-0000-000000000006"; // COMPLETED

    @Autowired
    private MockMvc mockMvc;

    // ─── 401 unauthenticated ──────────────────────────────────────────────────

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE))
                .andExpect(status().isUnauthorized());
    }

    // ─── 403 wrong role ──────────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER token receives 403 with no existence disclosure")
    void customerRole_returns403() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.customerBothAccountsJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("TECH_1 sees own today and carry-over jobs, not TECH_2 or COMPLETED")
    void tech1_seesOwnJobsOnly() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[*].id", hasItem(WO_TODAY_1)))
                .andExpect(jsonPath("$.data[*].id", hasItem(WO_TODAY_2)))
                .andExpect(jsonPath("$.data[*].id", hasItem(WO_CARRY_3)))
                .andExpect(jsonPath("$.data[*].id", hasItem(WO_CARRY_4)))
                // TECH_2 job absent — scope isolation
                .andExpect(jsonPath("$.data[*].id", not(hasItem(WO_TECH2_5))))
                // COMPLETED job absent — terminal state excluded
                .andExpect(jsonPath("$.data[*].id", not(hasItem(WO_DONE_6))));
    }

    @Test
    @DisplayName("Response envelope has required fields: data, page, links")
    void responseEnvelope_shape() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.page.totalElements").isNumber())
                .andExpect(jsonPath("$.page.size").isNumber())
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("Each item carries the required mobile projection fields")
    void item_projectionFields() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE).param("size", "1")
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isString())
                .andExpect(jsonPath("$.data[0].priority").isString())
                .andExpect(jsonPath("$.data[0].state").isString())
                .andExpect(jsonPath("$.data[0].resolutionDeadline").exists())
                .andExpect(jsonPath("$.data[0].slaAtRisk").isBoolean())
                // version must NOT appear in JSON response
                .andExpect(jsonPath("$.data[0].version").doesNotExist());
    }

    @Test
    @DisplayName("SLA at-risk flag is true for wo-DAY-0001 with past atRiskAt")
    void atRiskFlag_truForPastAtRiskAt() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + WO_TODAY_1 + "')].slaAtRisk",
                        hasItem(true)));
    }

    @Test
    @DisplayName("Contact phone is masked — last 4 digits only")
    void contactPhone_isMasked() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                // Phone "+44 20 1234 5678" → last 4 digits of stripped = "5678"
                .andExpect(jsonPath("$.data[?(@.id=='" + WO_TODAY_1 + "')].contactPhoneMasked",
                        hasItem("****5678")));
    }

    @Test
    @DisplayName("Asset tag is present for job with asset")
    void assetTag_presentForJobWithAsset() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + WO_TODAY_1 + "')].assetTag",
                        hasItem("HVAC-ALPHA-001")));
    }

    // ─── Empty day ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Technician with no jobs on a date returns empty data with 200")
    void tech1_emptyDay_returns200WithEmptyData() throws Exception {
        mockMvc.perform(get(URL).param("date", "2020-01-01")
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", empty()))
                .andExpect(jsonPath("$.page.totalElements", is(0)));
    }

    // ─── ETag / 304 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Response carries a strong ETag header")
    void response_hasETag() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"));
    }

    @Test
    @DisplayName("Matching If-None-Match returns 304 with no body")
    void conditionalGet_304_onMatchingEtag() throws Exception {
        // First request: get the ETag
        MvcResult first = mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");

        // Second request with If-None-Match: expect 304
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .header("If-None-Match", etag)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isNotModified());
    }

    // ─── Page-size clamping ───────────────────────────────────────────────────

    @Test
    @DisplayName("size=500 returns at most 50 items without error")
    void pageSize_clampedAt50() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE).param("size", "500")
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    // ─── Invalid sort field ───────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown sort field returns 400")
    void invalidSortField_returns400() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE).param("sort", "badField:ASC")
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isBadRequest());
    }

    // ─── Scope isolation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("TECH_2 sees only own job; TECH_1 jobs are absent")
    void tech2_doesNotSeeTech1Jobs() throws Exception {
        mockMvc.perform(get(URL).param("date", TEST_DATE)
                        .with(jwt()
                                .jwt(TestJwtFactory.tech2Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(WO_TECH2_5)))
                .andExpect(jsonPath("$.data[*].id", not(hasItem(WO_TODAY_1))))
                .andExpect(jsonPath("$.data[*].id", not(hasItem(WO_TODAY_2))));
    }

    // ─── Invalid date ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Malformed date parameter returns 400")
    void invalidDate_returns400() throws Exception {
        mockMvc.perform(get(URL).param("date", "not-a-date")
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isBadRequest());
    }
}
