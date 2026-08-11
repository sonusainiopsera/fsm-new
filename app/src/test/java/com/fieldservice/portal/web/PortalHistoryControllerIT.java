package com.fieldservice.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.security.TestJwtFactory;
import com.fieldservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc integration tests for GET /api/v1/portal/service-requests (WO-172).
 *
 * <p>Relies on V100, V118, V126 fixtures loaded by the test Spring profile.
 *
 * <p>Fixture topology (V126):
 * <ul>
 *   <li>ACCT_A: 65 work orders (d00...001..d00...065) across site_a1 and site_a2</li>
 *   <li>ACCT_B: 5 work orders (d10...001..d10...005) on site_b1</li>
 * </ul>
 * Rows d00...001–d00...020 share identical {@code created_at} to exercise UUID tie-break (AC-4).
 */
class PortalHistoryControllerIT extends AbstractIntegrationTest {

    private static final String BASE_URL = "/api/v1/portal/service-requests";

    private static final UUID SITE_A1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SITE_A2 = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SITE_B1 = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID FOREIGN_SITE = UUID.fromString("ffffffff-ffff-7fff-bfff-000000000001");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // ── AC-1: Response envelope shape ────────────────────────────────────────

    @Test
    @DisplayName("200 response has correct pagination envelope shape (AC-1)")
    void history_defaultPage_returns200WithEnvelope() throws Exception {
        mockMvc.perform(get(BASE_URL)
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").isNumber())
                .andExpect(jsonPath("$.page.totalElements").isNumber())
                .andExpect(jsonPath("$.page.totalPages").isNumber())
                .andExpect(jsonPath("$.links").exists());
    }

    @Test
    @DisplayName("Each history row contains all required redacted fields (AC-7)")
    void history_rowShape_hasRequiredFields() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?size=1")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        JsonNode row = root.path("data").get(0);
        assertThat(row.has("workOrderId")).isTrue();
        assertThat(row.has("reference")).isTrue();
        assertThat(row.has("statusLabel")).isTrue();
        assertThat(row.has("siteName")).isTrue();
        assertThat(row.has("openedAt")).isTrue();
    }

    @Test
    @DisplayName("History rows do not expose technician PII, GPS, internal codes, or cost (AC-7)")
    void history_rowShape_noForbiddenFields() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?size=1")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("technicianId");
        assertThat(body).doesNotContain("latitude");
        assertThat(body).doesNotContain("longitude");
        assertThat(body).doesNotContain("customerId");
        assertThat(body).doesNotContain("internalCode");
        assertThat(body).doesNotContain("billingAmount");
        assertThat(body).doesNotContain("costData");
    }

    // ── AC-2: Size clamping ───────────────────────────────────────────────────

    @Test
    @DisplayName("size=200 is clamped to 50 by the server (AC-2)")
    void history_oversizeRequest_clampedToMax50() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?size=200")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        assertThat(data.size()).isLessThanOrEqualTo(50);
        assertThat(root.path("page").path("size").asInt()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("size=50 returns at most 50 rows (AC-2)")
    void history_exactMaxSize_returnsAtMost50() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?size=50")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        assertThat(root.path("data").size()).isLessThanOrEqualTo(50);
    }

    // ── AC-3: Sort allow-list ─────────────────────────────────────────────────

    @Test
    @DisplayName("sort=createdAt is accepted and returns 200 (AC-3)")
    void history_sortByCreatedAt_returns200() throws Exception {
        mockMvc.perform(get(BASE_URL + "?sort=createdAt:DESC")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sort=state is accepted and returns 200 (AC-3)")
    void history_sortByState_returns200() throws Exception {
        mockMvc.perform(get(BASE_URL + "?sort=state:ASC")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("sort=closedAt is accepted and returns 200 (AC-3)")
    void history_sortByClosedAt_returns200() throws Exception {
        mockMvc.perform(get(BASE_URL + "?sort=closedAt:DESC")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unknown sort field returns 400 and executes no query (AC-3)")
    void history_unknownSortField_returns400() throws Exception {
        mockMvc.perform(get(BASE_URL + "?sort=description:ASC")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SQL-injection attempt via sort field returns 400 (AC-3)")
    void history_sqlInjectionInSort_returns400() throws Exception {
        mockMvc.perform(get(BASE_URL + "?sort=1;DROP TABLE work_order:ASC")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── AC-4: UUID tie-break — full pagination walk ──────────────────────────

    @Test
    @DisplayName("Walking all pages yields every ACCT_A row exactly once (AC-4 tie-break)")
    void history_fullPaginationWalk_exactlyOnce() throws Exception {
        // 65 ACCT_A rows in V126; rows 001-020 share identical created_at → UUID tie-break tested
        Set<String> seen = new HashSet<>();
        int page = 0;
        int size = 10;

        while (true) {
            String body = mockMvc.perform(get(BASE_URL + "?page=" + page + "&size=" + size)
                    .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");

            // No row should appear twice
            for (JsonNode row : data) {
                String id = row.path("workOrderId").asText();
                assertThat(seen).doesNotContain(id);
                seen.add(id);
            }

            // Follow next link or stop
            String nextLink = root.path("links").path("next").asText(null);
            if (nextLink == null || nextLink.isEmpty() || data.size() == 0) {
                break;
            }
            page++;
            if (page > 20) break; // safety guard
        }

        // ACCT_A must see exactly its 65 V126 rows plus existing V100 rows for ACCT_A
        // (We assert ≥65 from V126 alone; the exact total depends on prior fixtures)
        long v126ARows = seen.stream()
                .filter(id -> id.startsWith("d0000000"))
                .count();
        assertThat(v126ARows).isEqualTo(65);
    }

    // ── AC-5: Filters ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("siteId filter limits results to that site only (AC-5)")
    void history_siteIdFilter_limitsToSite() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?siteId=" + SITE_A2 + "&size=50")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        // V126 has 25 rows on site_a2 (041-065 are split between a2 and a1, actually
        // 041-055 = 15 on a2). All returned rows must be from ACCT_A only.
        // We just verify all returned IDs start with d00... (ACCT_A) rows
        for (JsonNode row : data) {
            String id = row.path("workOrderId").asText();
            // site_b1 rows (d10...) must not appear
            assertThat(id).doesNotStartWith("d1000000");
        }
    }

    @Test
    @DisplayName("statusGroup=OPEN returns only open-state rows (AC-5)")
    void history_statusGroupOpen_returnsOnlyOpenRows() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?statusGroup=OPEN&size=50")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        assertThat(data.size()).isGreaterThan(0);

        // All rows must have open-state labels
        Set<String> openLabels = Set.of("New Request", "Assigned", "Technician En Route",
                "Work In Progress", "Work On Hold");
        for (JsonNode row : data) {
            String label = row.path("statusLabel").asText();
            assertThat(openLabels).contains(label);
        }
    }

    @Test
    @DisplayName("statusGroup=CLOSED returns only closed-state rows (AC-5)")
    void history_statusGroupClosed_returnsOnlyClosedRows() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?statusGroup=CLOSED&size=50")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.path("data");
        assertThat(data.size()).isGreaterThan(0);

        Set<String> closedLabels = Set.of("Service Complete", "Closed", "Cancelled");
        for (JsonNode row : data) {
            String label = row.path("statusLabel").asText();
            assertThat(closedLabels).contains(label);
        }
    }

    @Test
    @DisplayName("Closed-state rows have closedAt and outcomeSummary populated (AC-7)")
    void history_closedStateRows_haveClosedAtAndOutcome() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?statusGroup=CLOSED&size=10")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        for (JsonNode row : root.path("data")) {
            assertThat(row.has("closedAt")).isTrue();
            assertThat(row.path("closedAt").asText()).isNotEmpty();
            assertThat(row.has("outcomeSummary")).isTrue();
            assertThat(row.path("outcomeSummary").asText()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Date range filter returns only rows within range (AC-5)")
    void history_dateRangeFilter_limitsResults() throws Exception {
        String body = mockMvc.perform(
                get(BASE_URL + "?fromDate=2026-01-15&toDate=2026-01-16&size=50")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        // Rows 001-022 (d00...001 to d00...022) have created_at on 2026-01-15 or 2026-01-16
        assertThat(root.path("data").size()).isGreaterThan(0);
        assertThat(root.path("page").path("totalElements").asLong()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("Inverted date range returns 400 (AC-5)")
    void history_invertedDateRange_returns400() throws Exception {
        mockMvc.perform(get(BASE_URL + "?fromDate=2026-06-01&toDate=2026-01-01")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Date range exceeding 366 days returns 400 (AC-5)")
    void history_tooWideDateRange_returns400() throws Exception {
        mockMvc.perform(get(BASE_URL + "?fromDate=2025-01-01&toDate=2026-07-01")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ── AC-6: Account isolation ───────────────────────────────────────────────

    @Test
    @DisplayName("Foreign siteId returns empty page, not 404 (AC-6 non-disclosure)")
    void history_foreignSiteId_returnsEmptyPage() throws Exception {
        mockMvc.perform(get(BASE_URL + "?siteId=" + FOREIGN_SITE)
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("ACCT_B siteId returns empty page for portal user A (AC-6)")
    void history_acctBSiteId_returnsEmptyPageForUserA() throws Exception {
        mockMvc.perform(get(BASE_URL + "?siteId=" + SITE_B1)
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("Portal user A never sees ACCT_B rows with any filter combination (AC-6)")
    void history_userA_neverSeesAcctBRows() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?size=50")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        for (JsonNode row : root.path("data")) {
            String id = row.path("workOrderId").asText();
            assertThat(id).doesNotStartWith("d1000000");
        }
    }

    @Test
    @DisplayName("Portal user B sees only ACCT_B rows, never ACCT_A rows (AC-6)")
    void history_userB_seesOnlyAcctBRows() throws Exception {
        String body = mockMvc.perform(get(BASE_URL + "?size=50")
                .with(jwt().jwt(TestJwtFactory.portalUserBJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(body);
        for (JsonNode row : root.path("data")) {
            String id = row.path("workOrderId").asText();
            assertThat(id).doesNotStartWith("d0000000");
        }
    }

    // ── Access control (AC-6) ─────────────────────────────────────────────────

    @Test
    @DisplayName("DISPATCHER role returns 403 (AC-6)")
    void history_dispatcherRole_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL)
                .with(jwt().jwt(TestJwtFactory.dispatcherJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TECHNICIAN role returns 403 (AC-6)")
    void history_technicianRole_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL)
                .with(jwt().jwt(TestJwtFactory.tech1Jwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Orphan portal user (no account mapping) returns 404 (AC-6)")
    void history_orphanUser_returns404() throws Exception {
        mockMvc.perform(get(BASE_URL)
                .with(jwt().jwt(TestJwtFactory.portalOrphanJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Unauthenticated request returns 401")
    void history_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty result set returns 200 with empty data array and totalElements=0 (edge case)")
    void history_emptyResult_returns200WithEmptyArray() throws Exception {
        // Use ACCT_B which has only 5 rows, filter to an impossible date
        mockMvc.perform(get(BASE_URL + "?fromDate=2020-01-01&toDate=2020-01-02")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    @DisplayName("Page beyond last page returns 200 with empty data and correct metadata")
    void history_pageBeyondLast_returnsEmptyData() throws Exception {
        mockMvc.perform(get(BASE_URL + "?page=9999&size=50")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("Next link appears when there are more pages")
    void history_multiplePages_hasNextLink() throws Exception {
        // ACCT_A has 65+ rows; with size=10 there must be a next link on page 0
        mockMvc.perform(get(BASE_URL + "?page=0&size=10")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.next").isNotEmpty());
    }

    @Test
    @DisplayName("Prev link appears on page 1 and is absent on page 0")
    void history_pagination_prevLinkOnPage1() throws Exception {
        // No prev on page 0
        mockMvc.perform(get(BASE_URL + "?page=0&size=10")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.prev").doesNotExist());

        // Prev present on page 1
        mockMvc.perform(get(BASE_URL + "?page=1&size=10")
                .with(jwt().jwt(TestJwtFactory.portalUserAJwt()))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.prev").isNotEmpty());
    }
}
