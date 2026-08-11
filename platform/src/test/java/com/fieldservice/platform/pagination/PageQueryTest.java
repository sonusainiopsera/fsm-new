package com.fieldservice.platform.pagination;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for PageQuery parameter binding, clamping, and SortAllowList.
 * Covers AC-2 (size clamping) and AC-3 (sort injection defence).
 */
@DisplayName("PageQuery unit tests")
class PageQueryTest {

    // ── Size clamping (AC-2) ──────────────────────────────────────────────────

    @Test
    @DisplayName("Size above 50 is clamped to 50")
    void size_above_max_is_clamped() {
        PageQuery q = new PageQuery(0, 9999, null, null);
        assertThat(q.size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("Size of exactly 50 is accepted")
    void size_of_50_is_accepted() {
        PageQuery q = new PageQuery(0, 50, null, null);
        assertThat(q.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("Size of 0 defaults to DEFAULT_SIZE")
    void size_of_zero_defaults_to_20() {
        PageQuery q = new PageQuery(0, 0, null, null);
        assertThat(q.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("Negative size defaults to DEFAULT_SIZE")
    void negative_size_defaults_to_20() {
        PageQuery q = new PageQuery(0, -1, null, null);
        assertThat(q.size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("Normal size within bounds is unchanged")
    void normal_size_is_unchanged() {
        PageQuery q = new PageQuery(0, 25, null, null);
        assertThat(q.size()).isEqualTo(25);
    }

    @Test
    @DisplayName("Negative page is clamped to 0")
    void negative_page_is_clamped_to_zero() {
        PageQuery q = new PageQuery(-5, 20, null, null);
        assertThat(q.page()).isZero();
    }

    // ── SortAllowList validation (AC-3) ──────────────────────────────────────

    private static final SortAllowList ALLOW_LIST = SortAllowList.of(Map.of(
        "createdAt", "createdAt",
        "title",     "title"
    ));

    @Test
    @DisplayName("Valid sort field resolves to its persistent property name")
    void valid_sort_field_resolves() {
        assertThat(ALLOW_LIST.resolve("createdAt")).isEqualTo("createdAt");
        assertThat(ALLOW_LIST.resolve("title")).isEqualTo("title");
    }

    @Test
    @DisplayName("Unlisted sort field throws InvalidSortException")
    void unlisted_sort_field_throws() {
        assertThatThrownBy(() -> ALLOW_LIST.resolve("state"))
            .isInstanceOf(InvalidSortException.class)
            .hasMessageContaining("state");
    }

    @Test
    @DisplayName("SQL injection attempt in sort field is rejected as unlisted")
    void sql_injection_sort_is_rejected() {
        assertThatThrownBy(() -> ALLOW_LIST.resolve("1 OR 1=1; DROP TABLE work_order;--"))
            .isInstanceOf(InvalidSortException.class);
    }

    @Test
    @DisplayName("Column expression injection in sort field is rejected")
    void column_expression_injection_is_rejected() {
        assertThatThrownBy(() -> ALLOW_LIST.resolve("id DESC; --"))
            .isInstanceOf(InvalidSortException.class);
    }

    @Test
    @DisplayName("allows() returns true for listed field")
    void allows_returns_true_for_listed_field() {
        assertThat(ALLOW_LIST.allows("createdAt")).isTrue();
        assertThat(ALLOW_LIST.allows("unknown")).isFalse();
    }

    // ── Sort building via SpecificationPageService helper (AC-4) ─────────────

    @Test
    @DisplayName("Default sort is createdAt DESC with id ASC tie-break")
    void default_sort_has_id_tie_break() {
        PageQuery query = new PageQuery(0, 20, null, null);
        var sort = SpecificationPageService.buildSortForTest(query, ALLOW_LIST);

        assertThat(sort.getOrderFor("createdAt")).isNotNull();
        assertThat(sort.getOrderFor("createdAt").getDirection().name()).isEqualTo("DESC");
        assertThat(sort.getOrderFor("id")).isNotNull();
        assertThat(sort.getOrderFor("id").getDirection().name()).isEqualTo("ASC");
    }

    @Test
    @DisplayName("Explicit sort=title,ASC still adds id ASC tie-break")
    void explicit_sort_adds_id_tie_break() {
        PageQuery query = new PageQuery(0, 20, "title,ASC", null);
        var sort = SpecificationPageService.buildSortForTest(query, ALLOW_LIST);

        assertThat(sort.getOrderFor("title")).isNotNull();
        assertThat(sort.getOrderFor("title").getDirection().name()).isEqualTo("ASC");
        assertThat(sort.getOrderFor("id")).isNotNull();
    }

    @Test
    @DisplayName("Invalid sort field in query causes InvalidSortException during sort building")
    void invalid_sort_field_in_query_throws() {
        PageQuery query = new PageQuery(0, 20, "hacked_field,ASC", null);
        assertThatThrownBy(() -> SpecificationPageService.buildSortForTest(query, ALLOW_LIST))
            .isInstanceOf(InvalidSortException.class)
            .extracting(e -> ((InvalidSortException) e).getField())
            .isEqualTo("hacked_field");
    }
}
