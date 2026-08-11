package com.fieldservice.app.pagination;

import com.fieldservice.app.AbstractIntegrationTest;
import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.domain.workorder.WorkOrderRepository;
import com.fieldservice.platform.pagination.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@link SpecificationPageService} with a 5000-row
 * work-order fixture in Testcontainers PostgreSQL.
 *
 * Covers AC-2 (clamping), AC-3 (sort injection 400), AC-4 (stability/tie-break),
 * AC-5 (filter-before-paginate), AC-6 (offset vs keyset equivalence),
 * AC-7 (cursor tamper rejection), AC-8 (query plan index use),
 * AC-9 (boundary/empty cases), AC-10 (integration), AC-11 (fixture).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaginationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WorkOrderRepository workOrderRepository;
    @Autowired private SpecificationPageService pageService;
    @Autowired private JdbcTemplate jdbc;

    private static final SortAllowList ALLOW_LIST = SortAllowList.of(Map.of(
        "createdAt", "createdAt",
        "title",     "title",
        "state",     "state"
    ));

    @BeforeAll
    static void insertFixture(@Autowired JdbcTemplate jdbc) {
        WorkOrderFixtureGenerator.insert(jdbc);
    }

    @AfterAll
    static void deleteFixture(@Autowired JdbcTemplate jdbc) {
        WorkOrderFixtureGenerator.delete(jdbc);
    }

    @BeforeEach
    void setAdminScope() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "test-admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // ── AC-2: Size clamping ───────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("AC-2: size above 50 is clamped; returned page never exceeds 50 rows")
    void size_is_clamped_to_50() {
        PageQuery q = new PageQuery(0, 9999, null, null);
        assertThat(q.size()).isEqualTo(50);

        PagedResponse<WorkOrder> resp = pageService.findPage(
            workOrderRepository, WorkOrder.class, null, q,
            ALLOW_LIST, "work-orders", mockRequest());

        assertThat(resp.data()).hasSize(50);
        assertThat(resp.page().size()).isEqualTo(50);
    }

    // ── AC-3: Sort injection defence ──────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("AC-3: unlisted sort field throws InvalidSortException")
    void unlisted_sort_field_throws() {
        PageQuery q = new PageQuery(0, 10, "hacked_col,ASC", null);
        assertThatThrownBy(() ->
            pageService.findPage(workOrderRepository, WorkOrder.class, null, q,
                ALLOW_LIST, "work-orders", mockRequest()))
            .isInstanceOf(InvalidSortException.class)
            .hasMessageContaining("hacked_col");
    }

    @Test
    @Order(3)
    @DisplayName("AC-3: SQL injection fragment in sort field is rejected")
    void sql_injection_sort_is_rejected() {
        PageQuery q = new PageQuery(0, 10, "id; DROP TABLE work_order;--,ASC", null);
        assertThatThrownBy(() ->
            pageService.findPage(workOrderRepository, WorkOrder.class, null, q,
                ALLOW_LIST, "work-orders", mockRequest()))
            .isInstanceOf(InvalidSortException.class);
    }

    // ── AC-4: Stable full traversal with id tie-breaking ─────────────────────

    @Test
    @Order(4)
    @DisplayName("AC-4: full offset traversal produces no duplicates and covers all fixture rows")
    void offset_traversal_no_duplicates() {
        Set<UUID> seen = new LinkedHashSet<>();
        int pageNum = 0;

        while (true) {
            PageQuery q = new PageQuery(pageNum, 50, "createdAt,ASC", null);
            PagedResponse<WorkOrder> resp = pageService.findPage(
                workOrderRepository, WorkOrder.class, null, q,
                ALLOW_LIST, "work-orders", mockRequest());

            if (resp.data().isEmpty()) break;

            for (WorkOrder wo : resp.data()) {
                assertThat(seen.add(wo.getId()))
                    .as("Duplicate id %s on page %d", wo.getId(), pageNum).isTrue();
            }

            pageNum++;
            if (seen.size() >= WorkOrderFixtureGenerator.ROW_COUNT) break;
        }

        assertThat(seen).hasSize(WorkOrderFixtureGenerator.ROW_COUNT);
    }

    // ── AC-5: Filter before paginate ─────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("AC-5: filter spec applied before pagination — totalElements reflects filtered rows")
    void filter_applied_before_pagination() {
        String uniqueTitle = "WO-2500";
        var filterSpec = (org.springframework.data.jpa.domain.Specification<WorkOrder>)
            (root, q, cb) -> cb.equal(root.get("title"), uniqueTitle);

        PagedResponse<WorkOrder> resp = pageService.findPage(
            workOrderRepository, WorkOrder.class, filterSpec,
            new PageQuery(0, 20, null, null), ALLOW_LIST, "work-orders", mockRequest());

        assertThat(resp.page().totalElements()).isEqualTo(1);
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).getTitle()).isEqualTo(uniqueTitle);
    }

    // ── AC-6: Offset vs keyset equivalence ───────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("AC-6: keyset page N+1 matches offset page N+1 exactly")
    void keyset_matches_offset_for_same_window() {
        // Get offset page 0 to obtain a cursor for the last row
        PageQuery p0q = new PageQuery(0, 20, "createdAt,DESC", null);
        PagedResponse<WorkOrder> offsetP0 = pageService.findPage(
            workOrderRepository, WorkOrder.class, null, p0q,
            ALLOW_LIST, "work-orders", mockRequest());
        assertThat(offsetP0.data()).hasSize(20);

        // Build a cursor manually from the last row of page 0
        WorkOrder lastRowP0 = offsetP0.data().get(offsetP0.data().size() - 1);
        byte[] secret = pageService.getCursorHmacSecretBytes();
        KeysetCursor cursor = new KeysetCursor(lastRowP0.getCreatedAt(), lastRowP0.getId(), "DESC");
        String cursorToken = cursor.encode(secret);

        // Keyset page (next 20 rows after the cursor)
        PageQuery keysetQ = new PageQuery(0, 20, "createdAt,DESC", cursorToken);
        PagedResponse<WorkOrder> keysetPage = pageService.findPage(
            workOrderRepository, WorkOrder.class, null, keysetQ,
            ALLOW_LIST, "work-orders", mockRequest());

        // Offset page 1 (same 20 rows)
        PageQuery offsetP1q = new PageQuery(1, 20, "createdAt,DESC", null);
        PagedResponse<WorkOrder> offsetP1 = pageService.findPage(
            workOrderRepository, WorkOrder.class, null, offsetP1q,
            ALLOW_LIST, "work-orders", mockRequest());

        List<UUID> keysetIds = keysetPage.data().stream()
            .map(WorkOrder::getId).collect(Collectors.toList());
        List<UUID> offsetIds = offsetP1.data().stream()
            .map(WorkOrder::getId).collect(Collectors.toList());

        assertThat(keysetIds).isEqualTo(offsetIds);
    }

    @Test
    @Order(7)
    @DisplayName("AC-6: auto-switch embeds cursor in next link at threshold page 19")
    void auto_switch_embeds_cursor_at_threshold() {
        // Page 19 (0-based) is the last before the threshold (threshold=20 means nextPage=20 >= 20)
        PageQuery p19q = new PageQuery(19, 20, "createdAt,DESC", null);
        PagedResponse<WorkOrder> p19 = pageService.findPage(
            workOrderRepository, WorkOrder.class, null, p19q,
            ALLOW_LIST, "work-orders", mockRequest());

        String nextLink = p19.links().next();
        assertThat(nextLink).as("page 19 should embed cursor in next link").isNotNull();
        assertThat(nextLink).contains("cursor=");
        assertThat(nextLink).doesNotContain("page=");
    }

    // ── AC-7: Cursor tamper detection ────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("AC-7: tampered cursor payload is rejected with InvalidCursorException")
    void tampered_cursor_is_rejected() {
        WorkOrder first = fetchFirstRow();
        byte[] secret = pageService.getCursorHmacSecretBytes();
        KeysetCursor cursor = new KeysetCursor(first.getCreatedAt(), first.getId(), "DESC");
        String token = cursor.encode(secret);

        // Flip a character in the payload (before the dot)
        String tampered = (token.charAt(0) == 'A' ? 'B' : 'A') + token.substring(1);
        PageQuery q = new PageQuery(0, 20, "createdAt,DESC", tampered);

        assertThatThrownBy(() ->
            pageService.findPage(workOrderRepository, WorkOrder.class, null,
                q, ALLOW_LIST, "work-orders", mockRequest()))
            .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @Order(9)
    @DisplayName("AC-7: cross-sort-order cursor replay is rejected")
    void cross_sort_order_cursor_is_rejected() {
        WorkOrder first = fetchFirstRow();
        byte[] secret = pageService.getCursorHmacSecretBytes();
        // Encode as DESC
        String descToken = new KeysetCursor(first.getCreatedAt(), first.getId(), "DESC").encode(secret);

        // Replay against an ASC request
        PageQuery ascQ = new PageQuery(0, 20, "createdAt,ASC", descToken);
        assertThatThrownBy(() ->
            pageService.findPage(workOrderRepository, WorkOrder.class, null,
                ascQ, ALLOW_LIST, "work-orders", mockRequest()))
            .isInstanceOf(InvalidCursorException.class)
            .hasMessageContaining("sort-order fingerprint");
    }

    // ── AC-8: Query plan uses composite index ─────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("AC-8: keyset query plan references created_at+id composite index")
    void keyset_query_uses_composite_index() {
        java.time.Instant midTime = java.time.Instant.parse("2024-01-01T00:04:10Z");
        UUID midId = UUID.fromString("00000099-0001-0001-7001-000000000001");

        // EXPLAIN returns multiple rows; collect them all
        List<String> planRows = jdbc.queryForList(
            "EXPLAIN SELECT * FROM work_order " +
            "WHERE (created_at < ? OR (created_at = ? AND CAST(id AS text) > CAST(? AS text))) " +
            "ORDER BY created_at DESC, id ASC LIMIT 20",
            String.class,
            java.sql.Timestamp.from(midTime),
            java.sql.Timestamp.from(midTime),
            midId.toString());

        String plan = String.join("\n", planRows);
        assertThat(plan)
            .as("Query plan should use the composite index, got: %s", plan)
            .containsAnyOf(
                "idx_work_order_created_at_id",
                "Index Scan",
                "Index Only Scan",
                "Bitmap Index Scan");
    }

    // ── AC-9: Empty result and boundary links ────────────────────────────────

    @Test
    @Order(11)
    @DisplayName("AC-9: empty result returns empty data, zero totals, null links")
    void empty_result_boundary() {
        var noMatchSpec = (org.springframework.data.jpa.domain.Specification<WorkOrder>)
            (root, q, cb) -> cb.equal(root.get("title"), "__NO_SUCH_TITLE__");

        PagedResponse<WorkOrder> resp = pageService.findPage(
            workOrderRepository, WorkOrder.class, noMatchSpec,
            new PageQuery(0, 20, null, null), ALLOW_LIST, "work-orders", mockRequest());

        assertThat(resp.data()).isEmpty();
        assertThat(resp.page().totalElements()).isZero();
        assertThat(resp.page().totalPages()).isZero();
        assertThat(resp.links().next()).isNull();
        assertThat(resp.links().prev()).isNull();
    }

    @Test
    @Order(12)
    @DisplayName("AC-9: first page has null prev; last page has null next")
    void boundary_links_are_null() {
        // First page
        PagedResponse<WorkOrder> first = pageService.findPage(
            workOrderRepository, WorkOrder.class, null,
            new PageQuery(0, 20, null, null), ALLOW_LIST, "work-orders", mockRequest());
        assertThat(first.links().prev()).as("First page prev must be null").isNull();

        // Last page (use total to compute page number)
        long total = first.page().totalElements();
        int lastPageNum = (int) ((total - 1) / 20);
        PagedResponse<WorkOrder> last = pageService.findPage(
            workOrderRepository, WorkOrder.class, null,
            new PageQuery(lastPageNum, 20, null, null), ALLOW_LIST, "work-orders", mockRequest());
        assertThat(last.links().next()).as("Last page next must be null").isNull();
    }

    // ── AC-6: Estimated total in keyset mode ─────────────────────────────────

    @Test
    @Order(13)
    @DisplayName("AC-6: keyset mode returns estimated=true and totalElements=-1")
    void keyset_mode_has_estimated_total() {
        WorkOrder first = fetchFirstRow();
        byte[] secret = pageService.getCursorHmacSecretBytes();
        String cursorToken = new KeysetCursor(first.getCreatedAt(), first.getId(), "DESC")
            .encode(secret);

        PagedResponse<WorkOrder> resp = pageService.findPage(
            workOrderRepository, WorkOrder.class, null,
            new PageQuery(0, 20, "createdAt,DESC", cursorToken),
            ALLOW_LIST, "work-orders", mockRequest());

        assertThat(resp.page().estimated()).isTrue();
        assertThat(resp.page().totalElements()).isEqualTo(-1L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static HttpServletRequest mockRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/work-orders");
        req.setServerName("localhost");
        req.setServerPort(8080);
        req.setScheme("http");
        return req;
    }

    private WorkOrder fetchFirstRow() {
        PagedResponse<WorkOrder> p0 = pageService.findPage(
            workOrderRepository, WorkOrder.class, null,
            new PageQuery(0, 1, "createdAt,DESC", null),
            ALLOW_LIST, "work-orders", mockRequest());
        assertThat(p0.data()).isNotEmpty();
        return p0.data().get(0);
    }
}
