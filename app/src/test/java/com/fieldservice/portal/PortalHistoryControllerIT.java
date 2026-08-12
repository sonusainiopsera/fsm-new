package com.fieldservice.portal;

import com.fieldservice.app.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import com.fieldservice.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@code GET /api/v1/portal/service-requests}.
 *
 * <p>Tests: 200 default page, pagination walk with next links, statusGroup filter,
 * siteId filter, date range validation, cross-account isolation (404),
 * wrong role (403), empty result, invalid sort 400, oversize clamping.
 */
@Tag("integration")
@Sql(scripts = {
        "classpath:fixtures/seed-core.sql",
        "classpath:fixtures/seed-wo172.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PortalHistoryControllerIT extends AbstractIntegrationTest {

    static final UUID USER_ACME_ID = UUID.fromString("00000000-0000-7019-8000-000000000001");
    static final UUID ACME_ID      = UUID.fromString("00000000-0000-7012-8000-000000000001");
    static final UUID USER_BLUE_ID = UUID.fromString("00000000-0000-7019-8000-000000000002");
    static final UUID BLUE_ID      = UUID.fromString("00000000-0000-7012-8000-000000000002");
    static final UUID ACME_SITE_1  = UUID.fromString("00000000-0000-7013-8000-000000000001");
    static final UUID ACME_SITE_2  = UUID.fromString("00000000-0000-7013-8000-000000000002");
    static final UUID BLUE_SITE_1  = UUID.fromString("00000000-0000-7013-8000-000000000005");

    @Autowired DatabaseCleaner dbCleaner;

    @AfterEach
    void clean() {
        dbCleaner.truncateAll();
    }

    // -------------------------------------------------------------------------
    // AC-1: 200 default page returns expected envelope
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-1: GET returns 200 with standard envelope structure")
    void listHistory_200_defaultPage() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").isNumber())
                .andExpect(jsonPath("$.page.totalPages").isNumber())
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("AC-1: Each row has the required redacted fields")
    void listHistory_200_rowHasRequiredFields() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests?size=1")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].workOrderId").isString())
                .andExpect(jsonPath("$.data[0].reference").isString())
                .andExpect(jsonPath("$.data[0].statusLabel").isString())
                .andExpect(jsonPath("$.data[0].siteName").isString())
                .andExpect(jsonPath("$.data[0].openedAt").isString())
                // Forbidden fields must be absent
                .andExpect(jsonPath("$.data[0].assignedTechnicianId").doesNotExist())
                .andExpect(jsonPath("$.data[0].technicianPhone").doesNotExist())
                .andExpect(jsonPath("$.data[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.data[0].score").doesNotExist())
                .andExpect(jsonPath("$.data[0].accountId").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // AC-2: Page-size clamping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-2: size=200 is clamped to 50 — data array never exceeds 50")
    void listHistory_sizeOverLimit_clamped() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests?size=200")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50))
                .andExpect(jsonPath("$.data.length()").value(lessThanOrEqualTo(50)));
    }

    // -------------------------------------------------------------------------
    // AC-3: Invalid sort field returns 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-3: Unknown sort field returns 400")
    void listHistory_invalidSort_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests?sort=technicianId:desc")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-3: Valid sort field createdAt:asc returns 200")
    void listHistory_validSort_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests?sort=createdAt:asc")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // AC-6: statusGroup filter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: statusGroup=OPEN returns only open-state work orders")
    void listHistory_statusGroupOpen_returnsOnlyOpen() throws Exception {
        String resp = mockMvc.perform(
                        get("/api/v1/portal/service-requests?statusGroup=OPEN&size=50")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(resp);
        for (var row : root.get("data")) {
            String label = row.get("statusLabel").asText();
            // Closed labels must not appear in OPEN results
            assertThat(label).isNotEqualTo("Work completed")
                             .isNotEqualTo("Service request closed")
                             .isNotEqualTo("Service request cancelled");
        }
    }

    @Test
    @DisplayName("AC-6: statusGroup=CLOSED returns only terminal-state work orders")
    void listHistory_statusGroupClosed_returnsOnlyClosed() throws Exception {
        String resp = mockMvc.perform(
                        get("/api/v1/portal/service-requests?statusGroup=CLOSED&size=50")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(resp);
        // Every closed row should have an outcomeSummary
        for (var row : root.get("data")) {
            assertThat(row.has("outcomeSummary")).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // AC-6: siteId filter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: siteId filter restricts results to that site")
    void listHistory_siteIdFilter_restrictsSite() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests?siteId=" + ACME_SITE_1 + "&size=50")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].siteName", everyItem(equalTo("Acme HQ"))));
    }

    @Test
    @DisplayName("AC-6: Foreign siteId (Bluestone site) returns 404")
    void listHistory_foreignSiteId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests?siteId=" + BLUE_SITE_1)
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // AC-6: Date range filter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-8: Inverted date range returns 400")
    void listHistory_invertedDateRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests"
                            + "?fromDate=2025-12-31T00:00:00Z&toDate=2025-01-01T00:00:00Z")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-8: Date range exceeding 365 days returns 400")
    void listHistory_dateRangeTooWide_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests"
                            + "?fromDate=2023-01-01T00:00:00Z&toDate=2025-12-31T00:00:00Z")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // AC-6 / AC-9: Cross-account isolation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-9: USER_BLUE sees only Bluestone work orders — no Acme rows")
    void listHistory_crossAccount_blueSeesOnlyBluestone() throws Exception {
        String resp = mockMvc.perform(get("/api/v1/portal/service-requests?size=50")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_BLUE_ID, List.of(BLUE_ID)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(resp);
        for (var row : root.get("data")) {
            String siteName = row.get("siteName").asText();
            assertThat(siteName).isEqualTo("Bluestone Tower");
        }
    }

    // -------------------------------------------------------------------------
    // AC-10: Wrong role returns 403
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-10: Dispatcher JWT returns 403")
    void listHistory_dispatcherRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests")
                        .with(jwt().jwt(TestJwtFactory.dispatcher())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-10: Unauthenticated request returns 401")
    void listHistory_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/portal/service-requests"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Edge case: empty result
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Edge: Customer with zero work orders returns 200 empty data array")
    void listHistory_emptyAccount_returns200Empty() throws Exception {
        // USER_BLUE with statusGroup=OPEN but using site 2 which Bluestone doesn't have
        // Actually use a future date range so no WOs match
        mockMvc.perform(get("/api/v1/portal/service-requests"
                            + "?fromDate=2099-01-01T00:00:00Z&toDate=2099-12-31T00:00:00Z")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    // -------------------------------------------------------------------------
    // AC-4: Full pagination walk — zero duplicates, zero omissions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: Walking all pages via next links returns each Acme WO exactly once")
    void listHistory_paginationWalk_noDuplicatesNoOmissions() throws Exception {
        // Acme has 64 work orders (001-064) — walk all pages at size=10
        Set<String>  seenIds = new HashSet<>();
        List<String> nextLinks = new ArrayList<>();

        // First page
        String firstResp = mockMvc.perform(
                        get("/api/v1/portal/service-requests?size=10&sort=createdAt:desc")
                        .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        collectIds(om.readTree(firstResp), seenIds);

        // Walk next links until exhausted (max 20 pages for safety)
        String nextLink = extractNextLink(om.readTree(firstResp));
        int guard = 0;
        while (nextLink != null && guard++ < 20) {
            // Strip base if present
            String path = nextLink.startsWith("http") ? nextLink : nextLink;
            String pageResp = mockMvc.perform(
                            get(path)
                            .with(jwt().jwt(TestJwtFactory.buildForCustomer(USER_ACME_ID, List.of(ACME_ID)))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            com.fasterxml.jackson.databind.JsonNode pageNode = om.readTree(pageResp);
            collectIds(pageNode, seenIds);
            nextLink = extractNextLink(pageNode);
        }

        // We must have seen all 64 Acme work orders
        assertThat(seenIds).hasSize(64);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void collectIds(com.fasterxml.jackson.databind.JsonNode root,
                                   Set<String> ids) {
        for (var row : root.get("data")) {
            String id = row.get("workOrderId").asText();
            assertThat(ids.add(id))
                    .as("Duplicate workOrderId found: %s", id)
                    .isTrue();
        }
    }

    private static String extractNextLink(com.fasterxml.jackson.databind.JsonNode root) {
        var links = root.get("links");
        if (links == null || links.get("next") == null || links.get("next").isNull()) {
            return null;
        }
        return links.get("next").asText();
    }
}
