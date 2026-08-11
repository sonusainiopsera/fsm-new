package com.fieldservice.pagination;

import com.fieldservice.security.AbstractIntegrationTest;
import com.fieldservice.security.TestJwtFactory;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the pagination stack against a 5000-row Testcontainers fixture.
 *
 * <p>Covers: envelope shape, size clamping, sort injection rejection, boundary links,
 * filter-before-paginate with scope, offset traversal stability, cursor tamper rejection.
 */
@Import(com.fieldservice.api.TestPaginationController.class)
class WorkOrderPaginationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Envelope shape
    // -------------------------------------------------------------------------

    @Test
    void response_envelope_contains_data_page_and_links() throws Exception {
        mockMvc.perform(get("/test/work-orders")
                        .param("size", "5")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.totalElements").isNumber())
                .andExpect(jsonPath("$.links").exists());
    }

    // -------------------------------------------------------------------------
    // Size clamping — server must never return more than 50 rows regardless of input
    // -------------------------------------------------------------------------

    @Test
    void size_above_fifty_is_clamped_server_side() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/work-orders")
                        .param("size", "999")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        List<?> data = JsonPath.read(body, "$.data");
        assertThat(data.size()).isLessThanOrEqualTo(50);
    }

    @Test
    void integer_max_value_size_is_still_clamped() throws Exception {
        mockMvc.perform(get("/test/work-orders")
                        .param("size", String.valueOf(Integer.MAX_VALUE))
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(50));
    }

    // -------------------------------------------------------------------------
    // Sort injection defence
    // -------------------------------------------------------------------------

    @Test
    void sort_on_unlisted_field_returns_400_with_field_error() throws Exception {
        mockMvc.perform(get("/test/work-orders")
                        .param("sort", "unknownField:ASC")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sort"));
    }

    @Test
    void sql_injection_attempt_in_sort_returns_400() throws Exception {
        mockMvc.perform(get("/test/work-orders")
                        .param("sort", "'; DROP TABLE work_order; --")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // -------------------------------------------------------------------------
    // Boundary links
    // -------------------------------------------------------------------------

    @Test
    void first_page_has_no_prev_link() throws Exception {
        mockMvc.perform(get("/test/work-orders")
                        .param("page", "0")
                        .param("size", "10")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.prev").doesNotExist());
    }

    @Test
    void last_page_has_no_next_link() throws Exception {
        MvcResult first = mockMvc.perform(get("/test/work-orders")
                        .param("page", "0")
                        .param("size", "20")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andReturn();

        int totalPages = ((Number) JsonPath.read(
                first.getResponse().getContentAsString(), "$.page.totalPages")).intValue();
        int lastPage = Math.max(0, totalPages - 1);

        mockMvc.perform(get("/test/work-orders")
                        .param("page", String.valueOf(lastPage))
                        .param("size", "20")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.next").doesNotExist());
    }

    @Test
    void page_far_beyond_last_returns_empty_data_not_error() throws Exception {
        mockMvc.perform(get("/test/work-orders")
                        .param("page", "99999")
                        .param("size", "20")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // -------------------------------------------------------------------------
    // Offset traversal stability — no duplicates across pages
    // -------------------------------------------------------------------------

    @Test
    void offset_traversal_produces_no_duplicate_ids() throws Exception {
        List<String> ids = collectOffsetIds(10, 20);
        Set<String> deduped = new LinkedHashSet<>(ids);
        assertThat(deduped.size())
                .as("Expected no duplicate IDs across %d pages", 10)
                .isEqualTo(ids.size());
    }

    // -------------------------------------------------------------------------
    // Cursor (keyset) pagination
    // -------------------------------------------------------------------------

    @Test
    void page_beyond_threshold_returns_keyset_metadata() throws Exception {
        // Go to page 21 — beyond the threshold of 20
        mockMvc.perform(get("/test/work-orders")
                        .param("page", "21")
                        .param("size", "20")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.estimated").value(true))
                .andExpect(jsonPath("$.page.totalElements").value(-1));
    }

    @Test
    void cursor_from_threshold_page_continues_traversal() throws Exception {
        // Get page 21 which is beyond the offset threshold
        MvcResult result = mockMvc.perform(get("/test/work-orders")
                        .param("page", "21")
                        .param("size", "20")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        List<String> idsPage21 = JsonPath.read(body, "$.data[*].id");
        assertThat(idsPage21).isNotEmpty();

        // Follow next cursor link if present
        String nextLink = extractNextLink(body);
        if (nextLink != null) {
            String cursor = extractCursorParam(nextLink);
            if (cursor != null) {
                MvcResult cursorResult = mockMvc.perform(
                                get("/test/work-orders")
                                        .param("cursor", cursor)
                                        .param("size", "20")
                                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                        .andExpect(status().isOk())
                        .andReturn();
                List<String> nextIds = JsonPath.read(cursorResult.getResponse().getContentAsString(), "$.data[*].id");
                // IDs on the next cursor page must not overlap with the current page
                assertThat(nextIds).doesNotContainAnyElementsOf(idsPage21);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cursor tamper rejection
    // -------------------------------------------------------------------------

    @Test
    void tampered_cursor_returns_400() throws Exception {
        mockMvc.perform(get("/test/work-orders")
                        .param("cursor", "invalidpayload.invalidsig")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cursor"));
    }

    @Test
    void garbage_cursor_returns_400() throws Exception {
        mockMvc.perform(get("/test/work-orders")
                        .param("cursor", "garbage!!!")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // totalElements reflects scope predicate, not whole table
    // -------------------------------------------------------------------------

    @Test
    void scoped_total_for_technician_is_less_than_dispatcher_total() throws Exception {
        MvcResult techResult = mockMvc.perform(get("/test/work-orders")
                        .param("size", "20")
                        .with(jwt().jwt(TestJwtFactory.tech1Jwt())))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult dispResult = mockMvc.perform(get("/test/work-orders")
                        .param("size", "20")
                        .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                .andExpect(status().isOk())
                .andReturn();

        long techTotal = ((Number) JsonPath.read(
                techResult.getResponse().getContentAsString(), "$.page.totalElements")).longValue();
        long dispTotal = ((Number) JsonPath.read(
                dispResult.getResponse().getContentAsString(), "$.page.totalElements")).longValue();

        assertThat(dispTotal).isGreaterThan(techTotal);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<String> collectOffsetIds(int maxPages, int size) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int page = 0; page < maxPages; page++) {
            MvcResult result = mockMvc.perform(get("/test/work-orders")
                            .param("page", String.valueOf(page))
                            .param("size", String.valueOf(size))
                            .with(jwt().jwt(TestJwtFactory.dispatcherJwt())))
                    .andExpect(status().isOk())
                    .andReturn();
            List<String> pageIds = JsonPath.read(result.getResponse().getContentAsString(), "$.data[*].id");
            if (pageIds.isEmpty()) break;
            ids.addAll(pageIds);
        }
        return ids;
    }

    private static String extractNextLink(String body) {
        try {
            Object next = JsonPath.read(body, "$.links.next");
            return next != null ? next.toString() : null;
        } catch (PathNotFoundException e) {
            return null;
        }
    }

    private static String extractCursorParam(String url) {
        int ci = url.indexOf("cursor=");
        if (ci < 0) return null;
        int end = url.indexOf('&', ci);
        String raw = end < 0 ? url.substring(ci + 7) : url.substring(ci + 7, end);
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
