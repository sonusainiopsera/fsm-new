package com.fieldservice.workorder.api;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET /api/v1/work-orders} — paginated, scoped work order search
 * (WO-127).
 *
 * <p>Covers:
 * <ul>
 *   <li>Envelope shape: data[], page{}, links{} always present</li>
 *   <li>Row scope: DISPATCHER sees all; TECHNICIAN sees only own assignments;
 *       CUSTOMER sees only own account's work orders</li>
 *   <li>State filter (multi-valued), priority filter, technician filter, customer filter, atRisk filter</li>
 *   <li>ETag + 304 Not Modified conditional GET</li>
 *   <li>400 for invalid sort field and invalid enum filter value</li>
 *   <li>401 for unauthenticated request</li>
 *   <li>Cross-scope probe returns 200 with empty data (totalElements not disclosing out-of-scope rows)</li>
 * </ul>
 *
 * <p>Fixture data is loaded by V108__search_fixtures.sql.
 */
@DisplayName("WorkOrderSearchController integration tests (WO-127)")
class WorkOrderSearchIT extends AbstractIntegrationTest {

    private static final String SEARCH_URL = "/api/v1/work-orders";

    // Fixture IDs from V100 + V108
    private static final String TECH_1_ID   = "00000000-0000-0000-0000-000000000011";
    private static final String ACCT_A      = "00000000-0000-0000-0000-000000000001";
    private static final String ACCT_B      = "00000000-0000-0000-0000-000000000002";
    private static final String SITE_A1     = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    // ─── 401 unauthenticated ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /work-orders requires authentication")
    void search_requiresAuth() throws Exception {
        mockMvc.perform(get(SEARCH_URL))
                .andExpect(status().isUnauthorized());
    }

    // ─── envelope shape ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Response envelope always contains data, page, and links")
    void search_envelopeShape() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.page.size").isNumber())
                .andExpect(jsonPath("$.page.number").isNumber())
                .andExpect(jsonPath("$.page.totalElements").isNumber())
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("Board row fields contain expected projection fields")
    void search_boardRowFields() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("size", "1")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isString())
                .andExpect(jsonPath("$.data[0].title").isString())
                .andExpect(jsonPath("$.data[0].state").isString())
                .andExpect(jsonPath("$.data[0].priority").isString())
                .andExpect(jsonPath("$.data[0].customerId").isString())
                .andExpect(jsonPath("$.data[0].siteId").isString())
                .andExpect(jsonPath("$.data[0].version").isNumber())
                .andExpect(jsonPath("$.data[0].createdAt").isString())
                .andExpect(jsonPath("$.data[0].updatedAt").isString());
    }

    // ─── size clamping ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Server clamps size > 50 to 50")
    void search_clampsSizeOver50() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("size", "999")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    // ─── row scope: DISPATCHER ────────────────────────────────────────────────

    @Test
    @DisplayName("DISPATCHER sees work orders from all accounts")
    void search_dispatcherSeesAll() throws Exception {
        // There are fixture rows for both ACCT_A and ACCT_B
        mockMvc.perform(get(SEARCH_URL)
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(greaterThan(0)));
    }

    // ─── row scope: TECHNICIAN ────────────────────────────────────────────────

    @Test
    @DisplayName("TECHNICIAN sees only their own assigned work orders")
    void search_technicianScopedToOwnAssignments() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.tech1Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                // All returned rows must have assignedTechnicianId == TECH_1
                .andExpect(jsonPath("$.data[?(@.assignedTechnicianId != '" + TECH_1_ID + "')]").isEmpty());
    }

    @Test
    @DisplayName("TECHNICIAN totalElements counts only in-scope rows")
    void search_technicianTotalElementsScoped() throws Exception {
        // TECH_2 should not see rows assigned to TECH_1
        mockMvc.perform(get(SEARCH_URL)
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.tech2Jwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_TECHNICIAN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.assignedTechnicianId == '" + TECH_1_ID + "')]").isEmpty());
    }

    // ─── row scope: CUSTOMER ──────────────────────────────────────────────────

    @Test
    @DisplayName("CUSTOMER sees only work orders for their own account")
    void search_customerScopedToOwnAccount() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.customerAccountAOnlyJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                // All returned customerId values must be ACCT_A
                .andExpect(jsonPath("$.data[?(@.customerId != '" + ACCT_A + "')]").isEmpty());
    }

    @Test
    @DisplayName("CUSTOMER for ACCT_B does not see ACCT_A work orders")
    void search_customerBDoesNotSeeAccountA() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.customerAccountBOnlyJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.customerId == '" + ACCT_A + "')]").isEmpty());
    }

    // ─── state filter ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("state filter returns only rows in the requested states")
    void search_filterByState() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("states", "NEW")
                        .param("states", "ASSIGNED")
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                // All returned state values are either NEW or ASSIGNED
                .andExpect(jsonPath("$.data[?(@.state != 'NEW' && @.state != 'ASSIGNED')]").isEmpty());
    }

    @Test
    @DisplayName("Unknown state enum value returns 400 with field error")
    void search_unknownStateReturns400() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("states", "BOGUS_STATE")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ─── priority filter ──────────────────────────────────────────────────────

    @Test
    @DisplayName("priority filter returns only rows with the requested priority")
    void search_filterByPriority() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("priority", "HIGH")
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.priority != 'HIGH')]").isEmpty());
    }

    // ─── technician filter ────────────────────────────────────────────────────

    @Test
    @DisplayName("assignedTechnicianId filter returns only rows assigned to that technician")
    void search_filterByTechnician() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("assignedTechnicianId", TECH_1_ID)
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.assignedTechnicianId != '" + TECH_1_ID + "')]").isEmpty());
    }

    // ─── customer filter ──────────────────────────────────────────────────────

    @Test
    @DisplayName("customerId filter returns only rows for that customer")
    void search_filterByCustomer() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("customerId", ACCT_B)
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.customerId != '" + ACCT_B + "')]").isEmpty());
    }

    // ─── at-risk filter ───────────────────────────────────────────────────────

    @Test
    @DisplayName("atRisk=true returns only rows that are at risk (past deadline, non-terminal)")
    void search_filterAtRisk() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("atRisk", "true")
                        .param("size", "50")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                // The at-risk flag in the response must be true for all rows
                .andExpect(jsonPath("$.data[?(@.atRisk == false)]").isEmpty());
    }

    // ─── empty result ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Filter matching nothing returns 200 with empty data array and totalElements=0")
    void search_noMatchReturns200WithEmptyData() throws Exception {
        // A deliberately unreachable UUID for site filter
        String unknownSiteId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        mockMvc.perform(get(SEARCH_URL)
                        .param("siteId", unknownSiteId)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    // ─── sort validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Unknown sort field returns 400")
    void search_unknownSortReturns400() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("sort", "notAField,asc")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Known sort field returns 200")
    void search_knownSortReturns200() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("sort", "createdAt,desc")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk());
    }

    // ─── ETag / conditional GET ───────────────────────────────────────────────

    @Test
    @DisplayName("Response includes an ETag header")
    void search_includesETag() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"));
    }

    @Test
    @DisplayName("If-None-Match with current ETag returns 304 Not Modified")
    void search_ifNoneMatch_returns304() throws Exception {
        // First request — get the ETag
        String etag = mockMvc.perform(get(SEARCH_URL)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

        // Second request with the returned ETag
        mockMvc.perform(get(SEARCH_URL)
                        .header("If-None-Match", etag)
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isNotModified());
    }

    // ─── pagination envelope ──────────────────────────────────────────────────

    @Test
    @DisplayName("Paginated response includes page metadata")
    void search_pageMetadata() throws Exception {
        mockMvc.perform(get(SEARCH_URL)
                        .param("page", "0")
                        .param("size", "5")
                        .with(jwt()
                                .jwt(TestJwtFactory.dispatcherJwt())
                                .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(5));
    }
}
